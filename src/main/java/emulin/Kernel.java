// ----------------------------------------
//  Emulin Kernel
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import java.util.*;
import emulin.*;
import emulin.device.*;

public class Kernel extends PipeManager {
  int cur_pid;
  int last_exit_code; // 最後に sys_exit に渡された終了コード
  boolean exec_request; // execのリクエストがあるかどうか
  String  exec_args[]; // execリクエスト用
  String  exec_envs[]; // execリクエスト用
  int exec_pid;  // execリクエスト用
  // issue #41 Phase 2: pty (/dev/ptmx + /dev/pts/N) 管理
  public final PtyManager pty = new PtyManager();

  // issue #401 Phase 1: 通信サンドボックス化 (TLS-MITM)。EMULIN_EGRESS_MITM=1 のとき
  //   boot() で生成 (default null = 無効、既存挙動不変)。amd64_connect / amd64_recvfrom が参照。
  public Egress egress;

  // issue #131 (tmux layer 14): AF_UNIX SOCK_STREAM の SCM_RIGHTS (fd passing)
  //   エミュレーション用。Java NIO の SocketChannel は ancillary data (cmsg) を
  //   露出しないため、同一 JVM 内 (= foreground `tmux new-session`: client が
  //   server を fork した親子) の sendmsg→recvmsg を「bind path 単位の FIFO
  //   queue」で橋渡しする。tmux client は自分の stdin/stdout (= 共有 console)
  //   tty fd を MSG_IDENTIFY_STDIN/STDOUT で server に渡し、server はそれで
  //   isatty() を通して CLIENT_TERMINAL を立てる。queue の要素は「渡された fd が
  //   渡す fd の種別を int[]{kind, ptn} で表す (kind: 0=STD / 1=ERR / 2=PTY_MASTER /
  //   3=PTY_SLAVE、ptn は pty 番号、console は ptn=-1)。queue 要素は int[][] = 1 sendmsg
  //   で渡された fd 群 (1 recvmsg が 1 グループを返す = sendmsg/recvmsg の 1:1 framing 保持)。
  //   issue #322: 旧実装は console
  //   (STD/ERR) のみで pty fd を drop し OpenSSH 10 privsep の PTY 受け渡し
  //   (mm_pty_allocate) が失敗していた。pty は受信側が PtyManager から再構築する。
  //   key は bind の native path (非空)。client→server 方向のみ作動 (server 側の
  //   getLocalAddress / client 側の getRemoteAddress が共に bind path で一致)。
  public final java.util.concurrent.ConcurrentHashMap<String,
      java.util.concurrent.ConcurrentLinkedQueue<int[][]>> pendingScmFds =
        new java.util.concurrent.ConcurrentHashMap<>();

  public Kernel( Sysinfo _sysinfo ) {
    ProcessInfo pinfo = new ProcessInfo( );
    // カーネルの初期化
    exec_request = false;
    sysinfo = _sysinfo;
    sysinfo.kernel = this;
    console = new emulin.device.Console( sysinfo );

    // プロセステーブルの初期化
    ptable = new Vector( );
  }

  // カーネルのブート
  public void boot( String args[], String _native_curdir ) {
    java.util.ArrayList<String> envList = new java.util.ArrayList<>();
    Process process;
    ProcessInfo pinfo;
    cur_pid = 1;

    // initプロセスの起動
    pinfo = new ProcessInfo( );
    pinfo.process = new Process( cur_pid, sysinfo );
    pinfo.process.set_init_process( );
    pinfo.ppid = 1;
    pinfo.process.setPriority( Thread.MIN_PRIORITY );
    pinfo.process.start( );
    // プロセステーブルへの登録
    ptable.addElement( (Object)pinfo );
    cur_pid++;

    // 環境変数の初期化 (基本セット)
    envList.add( "HOSTTYPE=i386" );
    // issue #191: guest の default PATH。従来は /usr/local/bin:/bin:/usr/bin:. で
    //   sbin が無く、dpkg 等が /usr/sbin の helper (ldconfig/start-stop-daemon) を
    //   見つけられず失敗していた。Debian root の標準 PATH (sbin 込み) にする。
    //   EMU_PATH 指定時は下の EMU_ ループが PATH= を追加するので二重定義を避けて
    //   ここでは追加しない。
    if( System.getenv( "EMU_PATH" ) == null ) {
        envList.add( "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/sbin:/usr/bin:/bin" );
    }
    envList.add( "SHELL=/bin/sh" );
    envList.add( "OSTYPE=Linux" );
    envList.add( "SHLVL=0" );
    // sandbox は既定で root user (uid=0) なので home は /root 固定。
    //   bash の `cd` (引数なし) / ssh の ~/.ssh / git の ~/.gitconfig /
    //   vim の ~/.vimrc 等が解決する。HOME を host から passthrough すると
    //   Windows host の HOME=C:\Users\... が漏れて guest で不正になるため、
    //   passthrough せず基本セットで与える (EMU_HOME で override 可)。
    // issue #611: 非 root ログイン (EMULIN_UID != 0、emulin.bat の choose_login で
    //   [2] <user> を選んだ場合) は HOME/USER/LOGNAME も root 固定だと不整合になる
    //   (cd が /root、~ が /root、プロンプト/設定パスが root 前提)。EMULIN_UID != 0 の
    //   ときは guest の /etc/passwd から該当 uid の entry を引き、USER/LOGNAME=ユーザ名・
    //   HOME=home dir にする。lookup 失敗時は /home/<uid> に fallback。uid=0 は従来どおり。
    String guestUser = "root", guestHome = "/root";
    if( RootSysinfo.default_uid != 0 ) {
      String[] pw = lookup_passwd_by_uid( RootSysinfo.default_uid );
      if( pw != null ) { guestUser = pw[0]; guestHome = pw[1]; }
      else { guestUser = "user"; guestHome = "/home/" + RootSysinfo.default_uid; }
    }
    envList.add( "HOME=" + guestHome );
    envList.add( "USER=" + guestUser );
    envList.add( "LOGNAME=" + guestUser );
    // issue #305: temp dir も guest 用に固定する。HOME と同様、passthrough すると
    //   Windows host の TEMP=C:\Users\...\Temp が漏れ、emacs(melpa) 等が temp file を
    //   Windows path で作ろうとして guest path に C:\ が混入 → Windows JVM の Paths.get()
    //   が InvalidPathException で native eval crash する。/tmp に固定して防ぐ。
    envList.add( "TMPDIR=/tmp" );
    envList.add( "TEMP=/tmp" );
    envList.add( "TMP=/tmp" );
    // LESSCHARSET は passthrough にあれば host から、なければ utf-8。
    if( System.getenv( "LESSCHARSET" ) == null ) {
        envList.add( "LESSCHARSET=utf-8" );  // legacy japanese-sjis から utf-8 に
    }
    envList.add( "LD_LIBRARY_PATH=/usr/local/lib" );
    envList.add( "TERMCAP=/etc/termcap" );
    // TERM は passthrough にあれば host から、なければ vt100 (普遍的 fallback)。
    if( System.getenv( "TERM" ) == null ) {
        envList.add( "TERM=vt100" );
    }

    // 一部の env var はホストから引き継ぎ。実機 OpenSSL や Python が
    //   挙動制御に使う変数を許可する。完全に全部素通しすると
    //   再現性が損なわれるので、明示的に列挙したものだけ。
    String[] passthrough = {
      "OPENSSL_ia32cap", "OPENSSL_CONF",
      "PYTHONHASHSEED", "PYTHONPATH",
      "LC_ALL", "TZ",   // LANG は issue #716 の専用ロジックで扱う (下記)
      "LD_DEBUG", "LD_DEBUG_OUTPUT",
      "LESSCHARSET", "LESS",  // less の設定 (LESSCHARSET=utf-8 デフォルト)
      "TERM",                  // terminfo lookup 用
    };
    for( String name : passthrough ) {
      String v = System.getenv( name );
      if( v != null ) envList.add( name + "=" + v );
    }
    // issue #716: LANG は他の passthrough 変数と違い「rootfs がそのロケールデータを持つか」を
    //   確認して引き継ぐ。理由は 2 つ:
    //   (a) host に LANG が無い (Windows / java 直起動 / 最小環境) と glibc が C (ASCII) ロケールに
    //       なり、bash readline / vim / ls で日本語 (UTF-8 マルチバイト) が化ける。
    //   (b) Linux host の LANG=ja_JP.UTF-8 等を素通しすると、rootfs に locales パッケージが無い
    //       場合 glibc の setlocale が失敗して結局 C に fallback し、ls が日本語ファイル名を
    //       \346.. とエスケープ表示する (0.7.0 bundle で実測)。「LANG はあるのに化ける」最悪形。
    //   → host LANG 無し = C.UTF-8 (glibc 組込みでロケールファイル不要、launcher の既定と同じ)。
    //     host LANG が C / POSIX / C.* = そのまま。それ以外は rootfs にロケールデータがある場合
    //     のみ素通し (guest で `apt-get install locales` 済みのケース)、無ければ C.UTF-8 に正規化
    //     する (UTF-8 の日本語入出力はこれで通る)。EMU_LANG 指定は従来どおり最優先 (glibc getenv
    //     は先頭一致だが、ここで積むとEMU_ 変換より先に並ぶため、指定時はここでは積まない)。
    if( System.getenv( "EMU_LANG" ) == null ) {
      String hostLang = System.getenv( "LANG" );
      String lang = "C.UTF-8";
      if( hostLang != null ) {
        if( hostLang.equals( "C" ) || hostLang.equals( "POSIX" )
            || hostLang.startsWith( "C." ) || has_rootfs_locale( hostLang ) ) {
          lang = hostLang;
        }
      }
      envList.add( "LANG=" + lang );
    }
    // EMU_<NAME> prefix のものを <NAME> に変換して emulated process に渡す。
    //   ホスト JVM の挙動を変えずに emulated 側だけ env を制御したい場合に使う。
    //   例: EMU_LD_PRELOAD=/lib/foo.so → emulated process は LD_PRELOAD=/lib/foo.so を受け取る。
    java.util.Map<String,String> env = System.getenv();
    for( java.util.Map.Entry<String,String> e : env.entrySet() ) {
      String k = e.getKey();
      if( k.startsWith("EMU_") ) {
        envList.add( k.substring(4) + "=" + e.getValue() );
      }
    }

    // issue #401 Phase 1: 通信サンドボックス化 (TLS-MITM)。EMULIN_EGRESS_MITM=1 で有効。
    //   ここで CA cert を rootfs に配置し、NODE_EXTRA_CA_CERTS + credential placeholder を
    //   guest env に注入する (実キー・CA 秘密鍵は host 側のまま)。placeholder は host env
    //   継承より先に積んで先勝ちさせる (glibc getenv 先頭一致)。
    if( Egress.enabled() ) {
      egress = new Egress();
      egress.prepareGuest( sysinfo, envList );
    }

    // issue #212: EMULIN_INHERIT_ENV=1 のとき、ホスト OS の既存環境変数を
    //   guest にも引き継ぐ。emulin が動作に必要とする変数 (PATH/HOME/USER/
    //   LOGNAME/SHELL/OSTYPE/SHLVL/HOSTTYPE/LD_LIBRARY_PATH/TERMCAP) と
    //   whitelist passthrough / EMU_ 由来の変数は既に上で envList に積んで
    //   あるので、ここでは「まだ無い名前」だけを末尾へ足す。glibc getenv は
    //   先頭一致なので、これで emulin の値が先勝ちし、host の Windows 値
    //   (PATH=C:\... / HOME=C:\Users\...) が guest を壊さない (issue 設計の
    //   「host を先に流し込み必須セットで上書き」と同じ観測結果を、env[0..] の
    //   既存順序を保ったまま実現する。argvdump64 回帰が先頭 5 entry の順序を
    //   固定しているため並べ替えできない)。
    //
    //   launcher (emulin.bat / emulin.sh) 経由起動でのみ launcher が 1 を set
    //   する。回帰テストは java 直起動 (flag 無し) なので default off = 現状
    //   維持で再現性を保つ。Windows 固有の不正 env (cmd の =C: / =ExitCode の
    //   ように名前が '=' を含む、名前/値に NUL・改行を含む) は除外する。
    //   Path (大小無視で PATH と重複) のような必須名の case 違いも emulin 値優先。
    if( "1".equals( System.getenv( "EMULIN_INHERIT_ENV" ) ) ) {
      java.util.HashSet<String> present = new java.util.HashSet<>();
      for( String entry : envList ) {
        int eq = entry.indexOf( '=' );
        present.add( eq >= 0 ? entry.substring( 0, eq ) : entry );
      }
      // emulin が必ず制御する変数名 (case 無視で host 側の重複を弾く)。
      java.util.HashSet<String> reserved = new java.util.HashSet<>( java.util.Arrays.asList(
        "PATH", "HOME", "USER", "LOGNAME", "SHELL",
        "OSTYPE", "SHLVL", "HOSTTYPE", "LD_LIBRARY_PATH", "TERMCAP",
        "TMPDIR", "TEMP", "TMP" ) );   // issue #305: Windows host の C:\ temp 値を弾く (emulin /tmp 優先)
      for( java.util.Map.Entry<String,String> e : env.entrySet() ) {
        String k = e.getKey(), v = e.getValue();
        if( k == null || v == null || k.isEmpty() ) continue;
        if( k.startsWith( "EMU_" ) ) continue;                 // 上で prefix 除去して渡し済
        if( k.startsWith( "EMULIN_CRED_" ) ) continue;         // issue #401: 実キーは host 側のみ、guest に絶対出さない
        if( k.indexOf( '=' ) >= 0 ) continue;                  // cmd の =C: 等、env 名として不正
        if( k.indexOf( '\n' ) >= 0 || k.indexOf( '\0' ) >= 0 ) continue;
        if( v.indexOf( '\n' ) >= 0 || v.indexOf( '\0' ) >= 0 ) continue; // 値の NUL/改行
        if( present.contains( k ) ) continue;                  // whitelist passthrough 等で設定済
        if( reserved.contains( k.toUpperCase( java.util.Locale.ROOT ) ) ) continue;
        envList.add( k + "=" + v );
      }
    }

    // issue #226: sshd を EMULIN_INHERIT_ENV 付きで起動するとき、guest env (上で
    //   ホスト OS の env を継承済) を ~/.ssh/environment へ書き出す。sshd は SSH
    //   session 用に env を新規構築し、親 sshd プロセスの env を継承しないため、
    //   これと sshd_config の `PermitUserEnvironment yes` で session に host env を
    //   渡す (#212 の直接起動と同じ env を sshd 越しでも得られるようにする)。
    if( args.length > 0 && "1".equals( System.getenv( "EMULIN_INHERIT_ENV" ) )
        && is_sshd_program( args[0] ) ) {
      write_sshd_user_environment( envList );
    }

    String envs[] = envList.toArray( new String[0] );

    // bootプロセスの生成 (init を親とする)
    pinfo = new ProcessInfo( );
    pinfo.ppid = 1;
    pinfo.process = new Process( cur_pid, sysinfo.get_default_gid( ), sysinfo.get_default_uid( ),
				 sysinfo.get_virtual_path( _native_curdir ),
				 args, envs, sysinfo, null );
    // issue #15: fd の access mode を正しく設定する。fcntl(fd, F_GETFL) は
    //   GetModeBit(fd) を返すので、stdin=O_RDONLY / stdout・stderr=O_WRONLY に
    //   しないと、funzip 等が「stdout が O_RDONLY = 書けない」と誤判定する。
    pinfo.process.syscall.FileOpen( "<std>", "r", Syscall.O_RDONLY ); // fd 0
    pinfo.process.syscall.FileOpen( "<std>", "w", Syscall.O_WRONLY ); // fd 1
    pinfo.process.syscall.FileOpen( "<err>", "w", Syscall.O_WRONLY ); // fd 2
    // プロセスの起動
    pinfo.process.start( );
    // プロセステーブルへの登録
    ptable.addElement( (Object)pinfo );
    cur_pid++;

    setPriority( Thread.MIN_PRIORITY );
  }

  // issue #226: program path の basename が "sshd" か (/usr/sbin/sshd 等)。
  private boolean is_sshd_program( String path ) {
    if( path == null ) return false;
    int slash = path.lastIndexOf( '/' );
    return ( slash >= 0 ? path.substring( slash + 1 ) : path ).equals( "sshd" );
  }

  // issue #611: guest の /etc/passwd から uid に一致する entry の { ユーザ名, home } を返す。
  //   非 root ログイン時の HOME/USER/LOGNAME 解決に使う。見つからない/読めない場合は null。
  //   passwd 行: name:passwd:uid:gid:gecos:home:shell (': ' 区切り、6 field 以上)。
  private String[] lookup_passwd_by_uid( int uid ) {
    try {
      String nat = sysinfo.get_native_path( "/etc/passwd" );
      java.util.List<String> lines = java.nio.file.Files.readAllLines( java.nio.file.Paths.get( nat ) );
      String want = Integer.toString( uid );
      for( String line : lines ) {
        String[] f = line.split( ":", -1 );
        if( f.length >= 6 && f[2].trim().equals( want ) ) {
          String name = f[0].trim();
          String home = f[5].trim();
          if( name.isEmpty() ) return null;
          if( home.isEmpty() ) home = "/home/" + name;
          return new String[]{ name, home };
        }
      }
    } catch( Throwable ignore ) {}
    return null;
  }

  // issue #226: guest env を ~/.ssh/environment (= sandbox の /root/.ssh/environment)
  //   へ書き出す。sshd + PermitUserEnvironment が session 開始時に読み込んで
  //   session の env に足す。TERM 等 session/client が管理する変数は除外する
  //   (TERM を上書きすると client の pty-req TERM を潰し #216 の修飾キーが壊れる)。
  // issue #716: rootfs が指定ロケールのデータを持つか。locales パッケージ導入済みなら
  //   /usr/lib/locale/locale-archive (locale-gen の出力先) がある。個別 dir 形式
  //   (localedef --no-archive) は glibc 慣例で codeset が小文字・ハイフン無し
  //   ("ja_JP.UTF-8" → dir 名 "ja_JP.utf8") なので両方の名前を見る。
  private boolean has_rootfs_locale( String locale ) {
    try {
      // issue #717: guest の locale-gen (archive モード) が 0 バイトの locale-archive を残す
      //   バグがあるため、サイズ 0 の archive は「無し」として扱う (壊れた archive を理由に
      //   ja_JP 等を素通しすると結局 C fallback で化ける)。
      if( new java.io.File( sysinfo.get_native_path( "/usr/lib/locale/locale-archive" ) ).length() > 0 ) return true;
      if( new java.io.File( sysinfo.get_native_path( "/usr/lib/locale/" + locale ) ).exists() ) return true;
      int dot = locale.indexOf( '.' );
      if( dot >= 0 ) {
        String norm = locale.substring( 0, dot ) + "."
                    + locale.substring( dot + 1 ).replace( "-", "" ).toLowerCase( java.util.Locale.ROOT );
        if( new java.io.File( sysinfo.get_native_path( "/usr/lib/locale/" + norm ) ).exists() ) return true;
      }
    } catch( Throwable ignore ) {}   // boot 中の path 解決失敗は「無し」に倒す (C.UTF-8 側が安全)
    return false;
  }

  private void write_sshd_user_environment( java.util.ArrayList<String> envList ) {
    java.util.HashSet<String> exclude = new java.util.HashSet<>( java.util.Arrays.asList(
      "TERM", "SHELL", "SHLVL", "PWD", "OLDPWD", "_",
      "SSH_CLIENT", "SSH_CONNECTION", "SSH_TTY", "SSH_AUTH_SOCK", "SSH_ORIGINAL_COMMAND" ) );
    StringBuilder sb = new StringBuilder();
    for( String entry : envList ) {
      int eq = entry.indexOf( '=' );
      String name = ( eq >= 0 ) ? entry.substring( 0, eq ) : entry;
      if( exclude.contains( name ) ) continue;
      // ~/.ssh/environment は 1 行 1 NAME=value。改行/NUL 入りは不正なので除外。
      if( entry.indexOf( '\n' ) >= 0 || entry.indexOf( '\0' ) >= 0 ) continue;
      sb.append( entry ).append( '\n' );
    }
    byte[] data = sb.toString().getBytes( java.nio.charset.StandardCharsets.UTF_8 );
    // root は常に。加えて issue #380 の非 root ユーザー (/etc/emulin-user) にも書く。
    //   sshd は login user の $HOME/.ssh/environment を読むため、root だけに書くと
    //   その user で ssh したセッションに env (issue #401 の placeholder 等) が届かない。
    //   file に入るのは placeholder であって実キーではない (実キーは host 側のみ) ので
    //   ここでの mode/所有権は秘密保護上重要でなく、emulin は guest uid で read を
    //   DAC 制限しないため kiyoka(uid 1000) からも読める。
    write_env_file( "/root/.ssh", data );
    String nru = read_nonroot_user( );
    if( nru != null ) write_env_file( "/home/" + nru + "/.ssh", data );
  }

  // ~/.ssh/environment を 1 件書く (dir mkdir → Files.write → InodeCache invalidate)。
  private void write_env_file( String sshDirVpath, byte[] data ) {
    try {
      new java.io.File( sysinfo.get_native_path( sshDirVpath ) ).mkdirs();
      String fileNative = sysinfo.get_native_path( sshDirVpath + "/environment" );
      java.nio.file.Files.write( java.nio.file.Paths.get( fileNative ), data );
      InodeCache.invalidateWithParent( fileNative );  // issue #701: guest を経由しない file 書き込み
    } catch ( Exception e ) {
      // 書けなくても sshd 自体は動く (env 継承が効かないだけ) ので fatal にしない。
      if( sysinfo.verbose( ) ) println( "issue #226: " + sshDirVpath + "/environment write failed: " + e );
    }
  }

  // issue #380: 非 root ユーザー名を /etc/emulin-user から読む (無ければ null)。
  //   path traversal 防止に '/' / NUL を含む値は捨てる。1 行目のみ採用。
  private String read_nonroot_user( ) {
    try {
      java.io.File f = new java.io.File( sysinfo.get_native_path( "/etc/emulin-user" ) );
      if( !f.isFile( ) ) return null;
      String s = new String( java.nio.file.Files.readAllBytes( f.toPath( ) ),
                             java.nio.charset.StandardCharsets.UTF_8 ).trim( );
      int nl = s.indexOf( '\n' );
      if( nl >= 0 ) s = s.substring( 0, nl ).trim( );
      if( s.isEmpty( ) || s.indexOf( '/' ) >= 0 || s.indexOf( '\0' ) >= 0 ) return null;
      return s;
    } catch ( Exception e ) { return null; }
  }

  // issue #709 追補: boot プロセス (pid=2 = 起動引数のプログラム) が終了したら、残存 guest
  //   デーモンを待たずにセッションを終える (VM の poweroff 相当)。旧挙動は「全プロセス終了まで
  //   待つ」ため、guest 内で daemon (例: Sumibi 開発中に起動された mozc_server が accept4 で
  //   常駐) が生き残ると、claude /quit 後も java が返らず「終了が固まった」ように見えた。
  //   実 Linux の「シェルが終われば端末は返り daemon は裏で生存」に相当する UX を、
  //   per-invocation な Emulin では「boot 終了 = poweroff」で近似する。daemon の graceful
  //   終了は不要 (実 poweroff も同様)。EMULIN_EXIT_WITH_BOOT=1 (bundle の emulin.bat が設定)
  //   のときのみ有効。テスト/CI (env 無し) や sshd モード (sshd 自体が boot プロセス) は不変。
  private static final boolean EXIT_WITH_BOOT =
    "1".equals( System.getenv( "EMULIN_EXIT_WITH_BOOT" ) );

  // 終了シーケンス (console flush + native drain + close → System.exit)。
  private void exit_session( ) {
    // issue #72: System.exit 前に console を flush + drain する (詳細は呼び出し元コメント参照)。
    if( sysinfo.kernel != null && sysinfo.kernel.console != null ) {
      sysinfo.kernel.console.flush();
      if( sysinfo.kernel.console.is_native_tty() ) {
        int drain_ms = 200;
        String env = System.getenv( "EMULIN_EXIT_DRAIN_MS" );
        if( env != null ) { try { drain_ms = Integer.parseInt( env.trim() ); } catch( NumberFormatException e ) {} }
        if( drain_ms > 0 ) {
          try { Thread.sleep( drain_ms ); } catch( InterruptedException e ) {}
        }
      }
      sysinfo.kernel.console.close();
    }
    System.exit( last_exit_code );
  }

  // カーネルのメイン処理
  public void start( ) {
    for( ;; ) {
      if( exec_request ) {
	exec( exec_pid, exec_args, exec_envs );
	exec_request = false;
      }
      try { Thread.sleep( 1000L ); }
      catch( InterruptedException m ) { };
      Thread.yield( );

      if( sysinfo.verbose( )) {
	  println( "processes = " + processes( ) );
      }
      // issue #709 追補: boot プロセス終了で poweroff (opt-in、上のコメント参照)。
      if( EXIT_WITH_BOOT && processes( ) > 1 ) {
        ProcessInfo bp = get_pinfo( 2 );
        if( bp == null || bp.process == null || bp.process.is_exited( ) ) {
          if( sysinfo.verbose( )) println( "Kernel.start( ) boot process exited -> poweroff" );
          exit_session( );
        }
      }
      if( 1 >= processes( )) {
	// init プロセスを終了させる。
	ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( 0 );
	if( sysinfo.verbose( )) {
	    println( "Kernel.start( )  break" );
	}
	// issue #72: System.exit 前に console を flush + drain する。
	//   Windows native terminal では console host へのレンダリングが非同期で、
	//   write 直後に System.exit すると最後の出力が画面に出ない (ls -l 等)。
	//   flush + terminal.close では同期 drain しきれず、唯一 wall-clock 時間
	//   (sleep) が効くと実機調査で判明。native terminal のときだけ短い drain
	//   delay を入れる (Linux dumb terminal / pipe は対象外なので test 無影響)。
	//   delay は EMULIN_EXIT_DRAIN_MS で調整可 (default 200ms)。
	exit_session( );
      }
    }
  }

  // exec( )処理
  public synchronized void exec( int _pid, String _args[], String _envs[] ) {
    exec( _pid, null, _args, _envs );
  }

  /**
   * exec with explicit executable path (different from argv[0]).
   * busybox 等の applet 形式で argv[0] が applet 名で path とは異なる場合に使う。
   * _exec_path == null の場合は _args[0] を path として扱う (従来挙動)。
   */
  public synchronized void exec( int _pid, String _exec_path, String _args[], String _envs[] ) {
    Syscall syscall;
    ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( _pid-1 );
    int tmp_gid       = pinfo.process.gid;
    int tmp_uid       = pinfo.process.uid;
    String tmp_curdir = pinfo.process.get_curdir( );
    int tmp_umask     = pinfo.process.umask;      // issue #550: umask は exec 越しに保存
    Process oldProc   = pinfo.process;            // issue #550: SIG_IGN/blocked mask 引き継ぎ元
    /* /proc/self/exe → 親プロセスの実行ファイルパスに解決
       (busybox のパイプライン子プロセスで使われる) */
    if( _exec_path != null && "/proc/self/exe".equals( _exec_path ) ) {
      _exec_path = pinfo.process.name;
    }
    if( _exec_path == null && _args.length > 0 && "/proc/self/exe".equals( _args[0] ) ) {
      _args = _args.clone();
      _args[0] = pinfo.process.name;
    }
    /* file descriptor は exec 越しに保持する: 旧プロセスの run() で
       all_file_close() が走らないようフラグを立てる。 */
    pinfo.process.exec_replacing = true;
    pinfo.process.set_exit_flag( ); // プロセスを協調終了させる
    pinfo.process.interrupt( );
    syscall = pinfo.process.syscall; // バックアップする。
    /* Phase 27 step 39: FD_CLOEXEC が立った fd を exec 直前に閉じる。
       git の child notify pipe を閉じないと親が read(EOF) を受け取れず
       hang する。Syscall (= FileAccess) は新 Process と共有されるので
       new Process 作成前に閉じる必要がある。 */
    syscall.close_cloexec_files( );
    pinfo.process = new Process( _pid, tmp_gid, tmp_uid, tmp_curdir, _exec_path, _args, _envs, sysinfo, syscall ); // プロセスを生成
    // issue #550: execve 越しに保存すべきプロセス属性を引き継ぐ (Linux 仕様)。
    //   umask / SIG_IGN disposition / blocked signal mask は exec で失われない。
    //   handler 付き disposition は SIG_DFL にリセット (新 Process のデフォルト)、cwd は保存済。
    pinfo.process.umask = tmp_umask;
    pinfo.process.inheritExecSignalState( oldProc );
    pinfo.process.start( ); // プロセスをスタートする
  }

  // fork( )処理
  // issue #720: fork EAGAIN 案内の間引き用 (spawn burst で stderr が溢れないように)
  private volatile long lastForkEagainLogMs = 0;

  public synchronized int fork( Process _process ) {
    return fork( _process, 0, 0 );
  }
  public synchronized int fork( Process _process, long child_stack ) {
    return fork( _process, child_stack, 0 );
  }

  // clone(2) の child_stack 対応 fork。
  //   child_stack != 0 のとき子の rsp をそれに設定する。glibc の posix_spawn は
  //   clone(CLONE_VM|CLONE_VFORK, child_stack, ...) で子を生成し、clone syscall
  //   復帰直後の child path が「rsp = child_stack」前提で fn/arg を pop する。
  //   旧実装は child_stack を無視して親 rsp を継承させていたため、子が親 stack
  //   から garbage を読み、関数 epilogue (LEAVE) で rbp=0 → null deref で死んだ
  //   (emacs dired が ls --dired を spawn する経路で発覚)。通常 fork/vfork は
  //   child_stack=0 で従来通り親 rsp を継承する。
  public synchronized int fork( Process _process, long child_stack, long cloneFlags ) {
    Process process;
    try {
      process = _process.duplicate( );
    } catch( NativeCpuBackend.PoolExhaustedException pe ) {
      // issue #720: 32GB 窓枯渇 (issue #379) で fork 子の guest RAM pool が確保できない場合、
      //   旧実装は System.exit で JVM ごと (sshd 常駐なら全セッションごと) 落ちていた。Linux は
      //   リソース逼迫時に fork(2) を EAGAIN で失敗させるだけなので同じ縮退にする: この fork
      //   だけ失敗させ、親プロセス・他セッションは継続する。ここは duplicate() より前に子の
      //   登録 (pinfo/ptable/pipe_connection) を一切していないので巻き戻し不要。案内は連発
      //   (spawn burst) で溢れないよう 5 秒に 1 回に間引く。
      long now = System.currentTimeMillis();
      if( now - lastForkEagainLogMs > 5000 ) {
        lastForkEagainLogMs = now;
        System.err.println( "[native] fork: guest RAM pool 確保失敗 (32GB 窓枯渇、issue #379) -> EAGAIN で親は継続 (issue #720)" );
        System.err.println( "[native]   恒久対策: EMULIN_NATIVE_POOL_MB=1024/512 で pool を小さくして同時プロセス余裕を増やす" );
      }
      return -11;   // -EAGAIN (Linux の fork(2) リソース逼迫時と同じ errno)
    }
    // issue #580: clone の単独共有フラグ (CLONE_FILES/FS/SIGHAND) を子に適用する。
    //   フラグ無し (通常 fork) は no-op = 挙動不変。fd table を共有する場合は下の
    //   pipe_connection (fd table 複製) を行わない。
    process.applyCloneSharing( _process, cloneFlags );
    // issue #113 (同 class の防御修正): fork/posix_spawn を worker thread (Thread64) が
    //   呼んだ場合、子は「呼び出した worker」の register/rip を継承すべき。だが
    //   _process.duplicate() は _process.cpu (= main thread 固定) を複製するため、
    //   worker が呼ぶと子が main の rip を継承して生成直後に wild jump する
    //   (clone の親 Cpu64 取り違え #181 と同一 class の潜在バグ)。呼び出しが Thread64
    //   worker なら子 cpu の register を worker のものに上書きする (copy_state_from は
    //   register/rip/flags/xmm のみコピーし、duplicate が張った子 memory への接続は保つ)。
    //   i386 process は Thread64 を持たないので no-op (= 従来挙動)。
    {
      Thread cur = Thread.currentThread();
      if( cur instanceof Thread64 && process.cpu instanceof Cpu64 )
        ((Cpu64) process.cpu).copy_state_from( ((Thread64) cur).cpu );
    }
    ProcessInfo pinfo = new ProcessInfo( );
    pinfo.ppid = process.get_pid( );
    pinfo.process = process;

    if( sysinfo.verbose( )) {
      println( "fork( " + pinfo.ppid + " -> " + cur_pid + " ) " );
    }

    // プロセス情報の更新
    process.set_pid( cur_pid );
    process.cpu.set_ax( 0 );
    process.cpu.set_ip( process.cpu.get_ip( )+2 );   // 次のアドレスに進める。 fork用の int 命令からリターンしたところから
    process.ip = process.cpu.get_ip( );
    if( child_stack != 0 ) {
      process.cpu.set_sp( child_stack );             // clone(CLONE_VM): 子は専用 stack
    }

    // プロセステーブルへの登録
    ptable.addElement( (Object)pinfo );

    // パイプの接続処理。issue #580: CLONE_FILES で fd table を親と共有している場合は
    //   複製しない (共有テーブルをそのまま使う)。
    if( ( cloneFlags & 0x400L ) == 0 )
      process.syscall.pipe_connection( (FileAccess)_process.syscall );

    // 子プロセスのスタート
    process.start( );
    return( cur_pid++ );
  }

  // issue #435: vfork(clone CLONE_VM|CLONE_VFORK = posix_spawn)。fork() と違い:
  //   (1) メモリを複製せず共有する(duplicateVfork、OOM/storm 回避)
  //   (2) 子が execve/_exit するまで親スレッドを suspend する(vfork 意味論)
  //   注意: await 中に kernel ロックを保持してはならない。子の execve は kernel.exec
  //   (synchronized) を呼ぶため、親が this ロックを握ったまま待つと deadlock する。
  //   よって pid 採番と ptable 登録だけ synchronized(this) で行い、await はロック外で待つ。
  public int vfork( Process _process, long child_stack ) {
    Process child = _process.duplicateVfork( child_stack );
    ProcessInfo pinfo = new ProcessInfo( );
    pinfo.ppid    = _process.get_pid( );
    pinfo.process = child;

    // 子レジスタ設定は backend で分岐する:
    //   software (Cpu64): ここで rax=0 / rip=syscall の次 / rsp=child_stack を設定。worker thread が
    //     呼んだ場合は子 cpu register を worker のものに補正(#113/#181 = clone 親取り違え対策)。
    //   native (NativeCpuBackend): duplicateVforkChild が clone-ABI register(親 RCX 戻り先 / child_stack /
    //     全 GPR snapshot)を設定済みなので、ここでは触らない(set_ax は no-op、set_ip は entryRip を壊す)。
    if( child.cpu instanceof Cpu64 ) {
      Thread cur = Thread.currentThread( );
      if( cur instanceof Thread64 )
        ((Cpu64) child.cpu).copy_state_from( ((Thread64) cur).cpu );
      child.cpu.set_ax( 0 );
      child.cpu.set_ip( child.cpu.get_ip( ) + 2 );
      child.ip = child.cpu.get_ip( );
      if( child_stack != 0 ) child.cpu.set_sp( child_stack );
    }

    // 親 suspend 用 latch を子に持たせる(子の execve/_exit が countDown する)
    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch( 1 );
    child.vforkLatch = latch;

    // 共有 Memory の syscall フィールドを窓の間だけ子のに向ける(mmap-with-fd 等の Memory 内
    //   fd 参照が親 fd テーブルを触るのを防ぐ)。親は suspend 中なので付替えは安全。
    Syscall savedMemSyscall = _process.mem.syscall;
    _process.mem.syscall = child.syscall;

    int childPid;
    synchronized( this ) {
      childPid = cur_pid++;
      child.set_pid( childPid );
      ptable.addElement( (Object)pinfo );
    }
    child.syscall.pipe_connection( (FileAccess)_process.syscall );
    child.start( );

    // 親 suspend: 子が execve/_exit するまで待つ(ロック非保持)
    try {
      latch.await( );
    } catch( InterruptedException e ) {
      Thread.currentThread( ).interrupt( );
    }

    // 親 resume: 共有 Memory の syscall を親のに戻す
    _process.mem.syscall = savedMemSyscall;
    return childPid;
  }

  // issue #709 診断: wait4 が長時間戻らないとき、親 ppid の子プロセスの状態を 1 行に
  //   まとめて返す (EMULIN_EPOLL_STUCK_MS の wait4-stuck dump 用)。どの子が exit して
  //   いないか / exec_replacing 残留かを凍結中に可視化する。
  public String debugChildren( int ppid ) {
    StringBuilder sb = new StringBuilder( "children:" );
    boolean any = false;
    for( int i = 0; i < ptable.size(); i++ ) {
      ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( i );
      if( pinfo == null || pinfo.ppid != ppid ) continue;
      any = true;
      if( pinfo.process == null ) {
        sb.append( " {pid=" + (i+1) + " REAPED(process=null)}" );
      } else {
        sb.append( " {pid=" + (i+1) + " exited=" + pinfo.process.is_exited()
          + " exec_replacing=" + pinfo.process.exec_replacing
          + " exit_code=" + pinfo.process.exit_code + "}" );
      }
    }
    if( !any ) sb.append( " (none)" );
    return sb.toString();
  }

  // issue #709 診断: 生存中の全プロセスを 1 行ずつ列挙 (stuck dump 用)。凍結時に
  //   「どの子プロセス (名前) が生きているか」を可視化する。
  public String debugProcs( ) {
    StringBuilder sb = new StringBuilder();
    for( int i = 0; i < ptable.size(); i++ ) {
      ProcessInfo pi = (ProcessInfo)ptable.elementAt( i );
      if( pi == null || pi.process == null ) continue;
      if( pi.process.is_exited( ) ) continue;
      sb.append( "    pid=" ).append( i + 1 ).append( " ppid=" ).append( pi.ppid )
        .append( " name=" ).append( pi.process.name )
        .append( " threads=" ).append( pi.process.active_thread_count.get() ).append( '\n' );
    }
    return sb.toString();
  }

  // pid の子プロセスが終了したかを調べる処理
  // 戻り値 : 0  .... 該当プロセス無し
  //          1>= ... 終了したプロセスを返す
  //         -1  .... プロセスが終了していない
  public int is_child_exited( int pid ) {
    int i;
    int ret = 0;
    // プロセステーブルをなめる
    for( i = 0 ; i < ptable.size( ) ; i++ ) {
      ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( i );
      if( pinfo.process != null ) {
	if( pid == pinfo.ppid ) {
	  // exec_replacing 中の旧プロセスは「終了」ではなく差し替え途中なので
	  // 親の wait4 から見えてはいけない (commit acc25d8 の race 対策)。
	  if( pinfo.process.exec_replacing ) {
	    ret = -1;
	    continue;
	  }
	  if( pinfo.process.is_exited( ))   {
	    ret = i+1;
	    pinfo.exit_code = pinfo.process.exit_code;
	    pinfo.term_sig  = pinfo.process.term_sig;   // issue #113: signal-kill (SIGSEGV) を退避
	    pinfo.process = null;
	    return( ret );
	  }
	  else                              { ret =  -1; }
	}
      }
      if( sysinfo.verbose( )) {
	if( pinfo.process == null ) {
	  println( "pid=" + (i+1) + " ppid=" + pinfo.ppid );
	}
	else {
	  println( "pid=" + (i+1) + " ppid=" + pinfo.ppid + " exit_flag= " + pinfo.process.is_exited( ));
	}
      }
    }
    if( sysinfo.verbose( )) {
      println( ret + " = is_child_exited( " + pid + " ) " );
    }
    return( ret );
  }

  // Phase 27 step 28: pthread (Thread64) の TID 採番。pid とは別空間で
  //   Linux の TID と同様に大きめから始める (pid と衝突しないよう 10000+)。
  private int next_tid_counter = 10000;
  public synchronized int next_tid( ) {
    return ++next_tid_counter;
  }

  // issue #469/#474: tgkill/tkill の ESRCH 判定用。個々の thread の生死までは
  //   追跡していない (registry が無い) ので、「これまでに一度でも割り当てられ得た
  //   tid/pid の上限」を超えていないかで簡易検証する。main thread の tid は pid と
  //   同値 (常にこの上限よりずっと小さい) なので誤検出せず、明らかに存在し得ない
  //   巨大な tid (テストの sentinel 値等) だけを弾ける。
  public synchronized boolean tid_ever_allocated( int tid ) {
    return tid > 0 && tid <= next_tid_counter;
  }

  // issue #473: setpgid の EPERM 判定用。指定 pgid が既存の(生きている)プロセスの
  //   process group として使われているかを調べる (pgrp 未設定なら自分の pid が
  //   実効 pgrp)。
  public synchronized boolean pgrp_exists( int pgid ) {
    for( int i = 0; i < ptable.size( ); i++ ) {
      ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( i );
      if( pinfo.process == null ) continue;
      int effective = ( pinfo.process.pgrp >= 0 ) ? pinfo.process.pgrp : pinfo.process.pid;
      if( effective == pgid ) return true;
    }
    return false;
  }

  // 指定 pid (1-based, ptable index+1) の ProcessInfo を返す。
  // wait4 が exit_code を読むのに使う。
  public ProcessInfo get_pinfo( int pid_1based ) {
    int idx = pid_1based - 1;
    if( idx < 0 || idx >= ptable.size( ) ) return null;
    return (ProcessInfo)ptable.elementAt( idx );
  }

  // issue #411: ptable のスロット数 (= 最大 1-based pid)。procfs の /proc 列挙が
  //   pid=1..ptable_size() を get_pinfo で走査して live process を列挙するのに使う。
  public int ptable_size( ) { return ptable.size( ); }

  // 指定 pid の Process を ptable から探す。なければ null。
  public synchronized Process find_process( int target_pid ) {
    for( int i = 0; i < ptable.size( ); i++ ) {
      ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( i );
      if( pinfo.process != null && pinfo.process.pid == target_pid ) {
        return pinfo.process;
      }
    }
    return null;
  }

  // issue #301: 指定 native path を open している全プロセスの host file handle
  //   (Fileinfo.f = RandomAccessFile) を強制 close する。close した数を返す。
  //   git clone を Ctrl-C 中断したとき、index-pack 子プロセスが書き込み中の
  //   tmp_pack を open したまま死にきれず、その handle がリークして Windows NTFS
  //   が「使用中」で後続の unlink を拒否する (rm -rf が EPERM)。Linux は open 中
  //   file も unlink 可能なので発動しない。FileAccess.unlink_with_retry が
  //   AccessDenied のときだけ呼ぶ防御策 (read-only=Phase 33-7 とは直交する原因)。
  //   path 比較は File 経由で OS の区切り文字 / case を正規化する。
  public synchronized int close_open_handles_for_path( String nativePath ) {
    if( nativePath == null ) return 0;
    java.io.File target = new java.io.File( nativePath );
    int closed = 0;
    for( int i = 0; i < ptable.size( ); i++ ) {
      ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( i );
      if( pinfo == null || pinfo.process == null || pinfo.process.syscall == null ) continue;
      java.util.Vector fl = pinfo.process.syscall.flist;   // Syscall extends FileAccess
      if( fl == null ) continue;
      // flist は当該プロセスの worker thread が触りうる。snapshot して走査する。
      Object[] snap;
      try { snap = fl.toArray( ); } catch( Exception e ) { continue; }
      for( int j = 0; j < snap.length; j++ ) {
        Fileinfo fi = (Fileinfo)snap[ j ];
        if( fi != null && fi.f != null && fi.name != null
            && new java.io.File( fi.name ).equals( target ) ) {
          try { fi.f.close( ); fi.f = null; closed++; } catch( Exception e ) {}
        }
      }
    }
    return closed;
  }

  // プロセスがいくら残っているかを返す
  // Phase 33-16: Ctrl-C で SIGINT を送る foreground プロセスを heuristic で
  // 決める。最も新しい non-init non-exited プロセスを foreground とみなす
  // (典型的に bash → fork → exec した child = vim 等)。
  public synchronized int find_foreground_pid( ) {
    for( int i = ptable.size() - 1; i >= 1; i-- ) {  // i=0 は init なので除外
      ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( i );
      if( pinfo == null || pinfo.process == null ) continue;
      if( pinfo.process.is_exited() ) continue;
      return pinfo.process.pid;
    }
    return -1;
  }

  // issue #3-#2: Ctrl-C 用 — bash の child (= git clone 等の non-shell 子プロセス)
  // が存在する場合のみその pid を返す。bash 単独実行中は -1。
  // Phase 33-17 で kill(-1) / kill(bash) が panic 経路に入る問題を回避するため、
  // bash interactive prompt 中は SIGINT 配信を skip し、stdin 経由 byte 0x03
  // による readline abort に委ねる。
  // bash が fork+exec で起動した child (git/curl/wget 等) は network read 等で
  // blocking するため、SIGINT 配信が必須。
  public synchronized int find_foreground_child_pid( ) {
    Process foreground = null;
    for( int i = ptable.size() - 1; i >= 1; i-- ) {
      ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( i );
      if( pinfo == null || pinfo.process == null ) continue;
      if( pinfo.process.is_exited() ) continue;
      foreground = pinfo.process;
      break;
    }
    if( foreground == null ) return -1;
    String n = foreground.name != null ? foreground.name : "";
    String ep = foreground.exec_path != null ? foreground.exec_path : "";
    // bash / sh 単独 (= interactive shell) は SIGINT を送らない (readline 自身
    // が byte 0x03 を処理する)。それ以外 (git/curl/vim 等) は SIGINT 配信。
    String basename_name = n;
    int sl = basename_name.lastIndexOf('/');
    if( sl >= 0 ) basename_name = basename_name.substring(sl + 1);
    String basename_ep = ep;
    sl = basename_ep.lastIndexOf('/');
    if( sl >= 0 ) basename_ep = basename_ep.substring(sl + 1);
    if( basename_name.equals("bash") || basename_name.equals("sh")
        || basename_name.equals("ash") || basename_name.equals("dash")
        || basename_ep.equals("bash") || basename_ep.equals("sh")
        || basename_ep.equals("ash") || basename_ep.equals("dash") ) {
      return -1;  // shell 単独 → byte 0x03 経路に委ねる
    }
    return foreground.pid;
  }

  public int processes( ) {
    int i;
    int ret = 0;
    // プロセステーブルをなめる
    for( i = 0 ; i < ptable.size( ) ; i++ ) {
      ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( i );
      if( pinfo.process != null ) {
	if( !pinfo.process.is_exited( )) {  ret++; }
      }
    }
    //    if( sysinfo.verbose( )) { System.out.print( "["+ret+"]" ); }
    return( ret );
  }

  // execのリクエストを行う
  public synchronized boolean exec_request( int _pid, String _args[], String _envs[] ) {
    boolean ret = false;
    if( !exec_request ) {
      exec_pid  = _pid;
      exec_args = _args;
      exec_envs = _envs;
      exec_request = true;
      ret = true;
    }
    return( ret );
  }

    // 指定デバイスファイルか？
    public synchronized boolean is_device( String _path ) {
	int index = _path.indexOf( "/dev/" );
	if( 0 == index ) { // マッチした
	    if( sysinfo.verbose( )) {
		println( "  " + _path + " is device " );
	    }
	    return( true );
	}
	if( sysinfo.verbose( )) {
	    println( "  " + _path + " is NOT device " );
	}
	return( false );
    }

    // 指定デバイスが存在するか調べる
    public synchronized String is_exist_device( String _path ) {
	if( 0 == _path.indexOf( "/dev/null" )) {
	    return( "<null>" ); // null デバイス
	}
	// /dev/urandom, /dev/random: 乱数デバイス。Bun (claude) 等が open+read する。
	//   sandbox に実体が無くても乱数を返す ("<urandom>" → Fileinfo.urandom_flag)。
	if( 0 == _path.indexOf( "/dev/urandom" ) || 0 == _path.indexOf( "/dev/random" )) {
	    return( "<urandom>" );
	}
	// Phase 30: /dev/tty を <std> (= 標準入出力 console) にルートする。
	// vim/emacs/less 等の対話 binary が /dev/tty を直接 open する経路で
	// JLine console (stdin keystroke + stdout escape sequence) と同じ
	// console を使えるようにする。
	if( 0 == _path.indexOf( "/dev/tty" )) {
	    return( "<std>" );
	}
	return( null );
    }


    // 指定 pid にシグナルを送る
    // _pid : 正の数の場合...シグナル _sig は,_pidにより識別されるプロセスに送られます。
    //        0 の場合     ...シグナル _sig は発信シグナルの属するグループのプロセスに送られます。
    //        -1の場合     ...シグナル _sig は最初のプロセス(init)を除くすべてのプロセスに送られる。
    //        -1未満の場合...シグナル _sig は,-_pidによって識別されるプロセスのグループに送られます。
    public synchronized boolean kill( int _pid, int _sig ) {
	int i;
	int ret = 0;
	if( -1 == _pid ) {
	    // pid 1 を除く全てのプロセスにシグナルを送信する。
	    for( i = 1 ; i < ptable.size( ) ; i++ ) {
		// 指定プロセスにシグナルを送信する。
		ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( i );
		if( pinfo.process != null ) {
		    pinfo.process.recv( _sig );
		    if( sysinfo.verbose( )) {
			println( "Emulin info: send to signal pid=( " + ((int)i+1) + " ) sig=( " + _sig + " ) " );
		    }
		}
	    }
	    return( true );
	}

	if( _pid < -1 ) {
	    // issue #225: kill(-pgid, sig) = プロセスグループ pgid 全員に配信 (POSIX)。
	    //   pty リサイズ時に foreground process group へ SIGWINCH を送るのに使う。
	    //   pgrp 未設定 (-1) のプロセスは pid 自身が pgrp leader。
	    int pgrp = -_pid;
	    for( i = 1 ; i < ptable.size( ) ; i++ ) {
		ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( i );
		if( pinfo.process != null ) {
		    int ppg = ( pinfo.process.pgrp >= 0 ) ? pinfo.process.pgrp : pinfo.process.pid;
		    if( ppg == pgrp ) pinfo.process.recv( _sig );
		}
	    }
	    return( true );
	}

	if( 0 < _pid ) {
	    // 指定プロセスにシグナルを送信する。
	    // Phase 27 step 23: 旧実装は ptable.elementAt(_pid) で 1 つズレていた
	    //   (pid は 1-based、ptable は 0-based)。pid を使って線形に探す。
	    Process target = find_process( _pid );
	    if( target != null ) target.recv( _sig );
	}
	else {
	    println( "Emulin error: kernel kill( " + _pid + " ) unsupported." );
	}
	return( true );
    }
}
