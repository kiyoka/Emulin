// ----------------------------------------
//  SyscallI386: i386 Linux ABI ディスパッチ層
//
//  i386 ABI 固有部分:
//    - システムコール番号テーブル (i386 entry.S 準拠)
//    - call() ディスパッチ (EAX=id, EBX=arg1, ECX=arg2, EDX=arg3, ESI=arg4, EDI=arg5)
//    - duplicate() (SyscallI386 インスタンスを複製)
//
//  共通の sys_* 実装は親クラス Syscall に残る。
// ----------------------------------------
package emulin;

public class SyscallI386 extends Syscall
{
  String syscall_name[];

  SyscallI386( Sysinfo _sysinfo, Process _process ) {
    super( _sysinfo, _process );
    int i = 0;
    syscall_name = new String[256];
    syscall_name[i++] = "sys_setup";		/* 0 */
    syscall_name[i++] = "sys_exit";
    syscall_name[i++] = "sys_fork";
    syscall_name[i++] = "sys_read";
    syscall_name[i++] = "sys_write";
    syscall_name[i++] = "sys_open";		/* 5 */
    syscall_name[i++] = "sys_close";
    syscall_name[i++] = "sys_waitpid";
    syscall_name[i++] = "sys_creat";
    syscall_name[i++] = "sys_link";
    syscall_name[i++] = "sys_unlink";		/* 10 */
    syscall_name[i++] = "sys_execve";
    syscall_name[i++] = "sys_chdir";
    syscall_name[i++] = "sys_time";
    syscall_name[i++] = "sys_mknod";
    syscall_name[i++] = "sys_chmod";		/* 15 */
    syscall_name[i++] = "sys_chown";
    syscall_name[i++] = "sys_break";
    syscall_name[i++] = "sys_stat";
    syscall_name[i++] = "sys_lseek";
    syscall_name[i++] = "sys_getpid";		/* 20 */
    syscall_name[i++] = "sys_mount";
    syscall_name[i++] = "sys_umount";
    syscall_name[i++] = "sys_setuid";
    syscall_name[i++] = "sys_getuid";
    syscall_name[i++] = "sys_stime";		/* 25 */
    syscall_name[i++] = "sys_ptrace";
    syscall_name[i++] = "sys_alarm";
    syscall_name[i++] = "sys_fstat";
    syscall_name[i++] = "sys_pause";
    syscall_name[i++] = "sys_utime";		/* 30 */
    syscall_name[i++] = "sys_stty";
    syscall_name[i++] = "sys_gtty";
    syscall_name[i++] = "sys_access";
    syscall_name[i++] = "sys_nice";
    syscall_name[i++] = "sys_ftime";		/* 35 */
    syscall_name[i++] = "sys_sync";
    syscall_name[i++] = "sys_kill";
    syscall_name[i++] = "sys_rename";
    syscall_name[i++] = "sys_mkdir";
    syscall_name[i++] = "sys_rmdir";		/* 40 */
    syscall_name[i++] = "sys_dup";
    syscall_name[i++] = "sys_pipe";
    syscall_name[i++] = "sys_times";
    syscall_name[i++] = "sys_prof";
    syscall_name[i++] = "sys_brk";		/* 45 */
    syscall_name[i++] = "sys_setgid";
    syscall_name[i++] = "sys_getgid";
    syscall_name[i++] = "sys_signal";
    syscall_name[i++] = "sys_geteuid";
    syscall_name[i++] = "sys_getegid";		/* 50 */
    syscall_name[i++] = "sys_acct";
    syscall_name[i++] = "sys_phys";
    syscall_name[i++] = "sys_lock";
    syscall_name[i++] = "sys_ioctl";
    syscall_name[i++] = "sys_fcntl";		/* 55 */
    syscall_name[i++] = "sys_mpx";
    syscall_name[i++] = "sys_setpgid";
    syscall_name[i++] = "sys_ulimit";
    syscall_name[i++] = "sys_olduname";
    syscall_name[i++] = "sys_umask";		/* 60 */
    syscall_name[i++] = "sys_chroot";
    syscall_name[i++] = "sys_ustat";
    syscall_name[i++] = "sys_dup2";
    syscall_name[i++] = "sys_getppid";
    syscall_name[i++] = "sys_getpgrp";		/* 65 */
    syscall_name[i++] = "sys_setsid";
    syscall_name[i++] = "sys_sigaction";
    syscall_name[i++] = "sys_sgetmask";
    syscall_name[i++] = "sys_ssetmask";
    syscall_name[i++] = "sys_setreuid";		/* 70 */
    syscall_name[i++] = "sys_setregid";
    syscall_name[i++] = "sys_sigsuspend";
    syscall_name[i++] = "sys_sigpending";
    syscall_name[i++] = "sys_sethostname";
    syscall_name[i++] = "sys_setrlimit";	/* 75 */
    syscall_name[i++] = "sys_getrlimit";
    syscall_name[i++] = "sys_getrusage";
    syscall_name[i++] = "sys_gettimeofday";
    syscall_name[i++] = "sys_settimeofday";
    syscall_name[i++] = "sys_getgroups";	/* 80 */
    syscall_name[i++] = "sys_setgroups";
    syscall_name[i++] = "old_select";
    syscall_name[i++] = "sys_symlink";
    syscall_name[i++] = "sys_lstat";
    syscall_name[i++] = "sys_readlink";		/* 85 */
    syscall_name[i++] = "sys_uselib";
    syscall_name[i++] = "sys_swapon";
    syscall_name[i++] = "sys_reboot";
    syscall_name[i++] = "old_readdir";
    syscall_name[i++] = "old_mmap";		/* 90 */
    syscall_name[i++] = "sys_munmap";
    syscall_name[i++] = "sys_truncate";
    syscall_name[i++] = "sys_ftruncate";
    syscall_name[i++] = "sys_fchmod";
    syscall_name[i++] = "sys_fchown";		/* 95 */
    syscall_name[i++] = "sys_getpriority";
    syscall_name[i++] = "sys_setpriority";
    syscall_name[i++] = "sys_profil";
    syscall_name[i++] = "sys_statfs";
    syscall_name[i++] = "sys_fstatfs";		/* 100 */
    syscall_name[i++] = "sys_ioperm";
    syscall_name[i++] = "sys_socketcall";
    syscall_name[i++] = "sys_syslog";
    syscall_name[i++] = "sys_setitimer";
    syscall_name[i++] = "sys_getitimer";	/* 105 */
    syscall_name[i++] = "sys_newstat";
    syscall_name[i++] = "sys_newlstat";
    syscall_name[i++] = "sys_newfstat";
    syscall_name[i++] = "sys_uname";
    syscall_name[i++] = "sys_iopl";		/* 110 */
    syscall_name[i++] = "sys_vhangup";
    syscall_name[i++] = "sys_idle";
    syscall_name[i++] = "sys_vm86old";
    syscall_name[i++] = "sys_wait4";
    syscall_name[i++] = "sys_swapoff";		/* 115 */
    syscall_name[i++] = "sys_sysinfo";
    syscall_name[i++] = "sys_ipc";
    syscall_name[i++] = "sys_fsync";
    syscall_name[i++] = "sys_sigreturn";
    syscall_name[i++] = "sys_clone";		/* 120 */
    syscall_name[i++] = "sys_setdomainname";
    syscall_name[i++] = "sys_newuname";
    syscall_name[i++] = "sys_modify_ldt";
    syscall_name[i++] = "sys_adjtimex";
    syscall_name[i++] = "sys_mprotect";		/* 125 */
    syscall_name[i++] = "sys_sigprocmask";
    syscall_name[i++] = "sys_create_module";
    syscall_name[i++] = "sys_init_module";
    syscall_name[i++] = "sys_delete_module";
    syscall_name[i++] = "sys_get_kernel_syms";	/* 130 */
    syscall_name[i++] = "sys_quotactl";
    syscall_name[i++] = "sys_getpgid";
    syscall_name[i++] = "sys_fchdir";
    syscall_name[i++] = "sys_bdflush";
    syscall_name[i++] = "sys_sysfs";		/* 135 */
    syscall_name[i++] = "sys_personality";
    syscall_name[i++] = "no syscall( for_afs )";
    syscall_name[i++] = "sys_setfsuid";
    syscall_name[i++] = "sys_setfsgid";
    syscall_name[i++] = "sys_llseek";		/* 140 */
    syscall_name[i++] = "sys_getdents";
    syscall_name[i++] = "sys_select";
    syscall_name[i++] = "sys_flock";
    syscall_name[i++] = "sys_msync";
    syscall_name[i++] = "sys_readv";		/* 145 */
    syscall_name[i++] = "sys_writev";
    syscall_name[i++] = "sys_getsid";
    syscall_name[i++] = "sys_fdatasync";
    syscall_name[i++] = "sys_sysctl";
    syscall_name[i++] = "sys_mlock";		/* 150 */
    syscall_name[i++] = "sys_munlock";
    syscall_name[i++] = "sys_mlockall";
    syscall_name[i++] = "sys_munlockall";
    syscall_name[i++] = "sys_sched_setparam";
    syscall_name[i++] = "sys_sched_getparam";   /* 155 */
    syscall_name[i++] = "sys_sched_setscheduler";
    syscall_name[i++] = "sys_sched_getscheduler";
    syscall_name[i++] = "sys_sched_yield";
    syscall_name[i++] = "sys_sched_get_priority_max";
    syscall_name[i++] = "sys_sched_get_priority_min";  /* 160 */
    syscall_name[i++] = "sys_sched_rr_get_interval";
    syscall_name[i++] = "sys_nanosleep";
    syscall_name[i++] = "sys_mremap";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";        /* 165 */
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";        /* 170 */
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";        /* 175 */
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";        /* 180 */
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";        /* 185 */
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";
    syscall_name[i++] = "sys_call(non)";        /* 190 */
  }

  // 自分の複製を返す
  @Override
  public Syscall duplicate( Process _process ) {
    SyscallI386 _syscall = new SyscallI386( sysinfo, _process );
    _syscall.mem  = mem;
    _syscall.update_info( (FileAccess)this );
    return( _syscall );
  }

  // i386 ABI ディスパッチ: EAX=syscall番号, EBX/ECX/EDX/ESI/EDI=引数
  // 返り値は long で受け取り、呼び出し側 (Cpu) で int に切り詰める。
  @Override
  public long call( int id, long bx, long cx, long dx, long si, long di ) {
    long ret = 0;
    boolean done = false;
    if( sysinfo.verbose( )) {
      process.println( " System call id=[" + id + "] " + syscall_name[id] + "("
                     + Util.hexstr( bx, 8 ) + ","
                     + Util.hexstr( cx, 8 ) + ","
                     + Util.hexstr( dx, 8 ) + ","
                     + Util.hexstr( si, 8 ) + ","
                     + Util.hexstr( di, 8 ) + ")    evals = " + process.evals( ));
    }
    if( id ==   1 ) {      ret = sys_exit(  bx, cx, dx, si, di );           done = true; }
    if( id ==   2 ) {      ret = sys_fork(  bx, cx, dx, si, di );           done = true; }
    if( id ==   3 ) {      ret = sys_read(  bx, cx, dx, si, di );           done = true; }
    if( id ==   4 ) {      ret = sys_write(  bx, cx, dx, si, di );          done = true; }
    if( id ==   5 ) {      ret = sys_open(  bx, cx, dx, si, di );           done = true; }
    if( id ==   6 ) {      ret = sys_close(  bx, cx, dx, si, di );          done = true; }
    // waitpid(pid, status, options) = wait4(pid, status, options, NULL)。旧実装は
    //   id==7 未 dispatch で "Unsupported system call" になっていた (fork+wait 分離不可)。
    if( id ==   7 ) {      ret = sys_wait4(  bx, cx, dx, 0, 0 );            done = true; }
    if( id ==  10 ) {      ret = sys_unlink(  bx, cx, dx, si, di );         done = true; }
    if( id ==  11 ) {      ret = sys_execve(  bx, cx, dx, si, di );         done = true; }
    if( id ==  12 ) {      ret = sys_chdir(  bx, cx, dx, si, di );          done = true; }
    if( id ==  13 ) {      ret = sys_time(  bx, cx, dx, si, di );           done = true; }
    if( id ==  15 ) {      ret = sys_chmod(  bx, cx, dx, si, di );          done = true; }
    if( id ==  16 ) {      ret = sys_chown(  bx, cx, dx, si, di );          done = true; }
    if( id ==  19 ) {      ret = sys_lseek(  bx, cx, dx, si, di );          done = true; }
    if( id ==  20 ) {      ret = sys_getpid(  bx, cx, dx, si, di );         done = true; }
    if( id ==  21 ) {      ret = sys_mount(  bx, cx, dx, si, di );          done = true; }
    if( id ==  22 ) {      ret = sys_umount(  bx, cx, dx, si, di );         done = true; }
    if( id ==  23 ) {      ret = sys_setuid(  bx, cx, dx, si, di );         done = true; }
    if( id ==  24 ) {      ret = sys_getuid(  bx, cx, dx, si, di );         done = true; }
    if( id ==  27 ) {      ret = sys_alarm(  bx, cx, dx, si, di );          done = true; }
    if( id ==  29 ) {      ret = sys_pause(  bx, cx, dx, si, di );          done = true; }
    if( id ==  30 ) {      ret = sys_utime(  bx, cx, dx, si, di );          done = true; }
    if( id ==  33 ) {      ret = sys_access(  bx, cx, dx, si, di );         done = true; }
    if( id ==  36 ) {      ret = sys_sync(  bx, cx, dx, si, di );           done = true; }
    if( id ==  37 ) {      ret = sys_kill(  bx, cx, dx, si, di );           done = true; }
    if( id ==  38 ) {      ret = sys_rename(  bx, cx, dx, si, di );         done = true; }
    if( id ==  39 ) {      ret = sys_mkdir(  bx, cx, dx, si, di );          done = true; }
    if( id ==  40 ) {      ret = sys_rmdir(  bx, cx, dx, si, di );          done = true; }
    if( id ==  41 ) {      ret = sys_dup(  bx, cx, dx, si, di );            done = true; }
    if( id ==  42 ) {      ret = sys_pipe(  bx, cx, dx, si, di );           done = true; }
    if( id ==  43 ) {      ret = sys_times(  bx, cx, dx, si, di );          done = true; }
    if( id ==  45 ) {      ret = sys_brk(  bx, cx, dx, si, di );            done = true; }
    if( id ==  46 ) {      ret = sys_setgid(  bx, cx, dx, si, di );         done = true; }
    if( id ==  47 ) {      ret = sys_getgid(  bx, cx, dx, si, di );         done = true; }
    if( id ==  49 ) {      ret = sys_geteuid(  bx, cx, dx, si, di );        done = true; }
    if( id ==  50 ) {      ret = sys_getegid(  bx, cx, dx, si, di );        done = true; }
    if( id ==  54 ) {      ret = sys_ioctl(  bx, cx, dx, si, di );          done = true; }
    if( id ==  55 ) {      ret = sys_fcntl(  bx, cx, dx, si, di );          done = true; }
    if( id ==  57 ) {      ret = sys_setpgid(  bx, cx, dx, si, di );        done = true; }
    if( id ==  60 ) {      ret = sys_umask(  bx, cx, dx, si, di );          done = true; }
    if( id ==  63 ) {      ret = sys_dup2(  bx, cx, dx, si, di );           done = true; }
    if( id ==  64 ) {      ret = sys_getppid(  bx, cx, dx, si, di );        done = true; }
    if( id ==  65 ) {      ret = sys_getpgrp(  bx, cx, dx, si, di );        done = true; }
    if( id ==  66 ) {      ret = sys_setsid(  bx, cx, dx, si, di );         done = true; }
    if( id ==  67 ) {      ret = sys_sigaction(  bx, cx, dx, si, di );      done = true; }
    if( id ==  75 ) {      ret = sys_setrlimit(  bx, cx, dx, si, di );      done = true; }
    if( id ==  76 ) {      ret = sys_getrlimit(  bx, cx, dx, si, di );      done = true; }
    if( id ==  78 ) {      ret = sys_gettimeofday(  bx, cx, dx, si, di );   done = true; }
    if( id ==  80 ) {      ret = sys_getgroups(  bx, cx, dx, si, di );      done = true; }
    if( id ==  85 ) {      ret = sys_readlink(  bx, cx, dx, si, di );       done = true; }
    if( id ==  90 ) {      ret = sys_mmap(  bx, cx, dx, si, di );           done = true; }
    if( id ==  91 ) {      ret = sys_munmap(  bx, cx, dx, si, di );         done = true; }
    if( id ==  93 ) {      ret = sys_ftruncate(  bx, cx, dx, si, di );      done = true; }
    if( id ==  94 ) {      ret = sys_fchmod(  bx, cx, dx, si, di );         done = true; }
    if( id == 102 ) {      ret = sys_socketcall(  bx, cx, dx, si, di );     done = true; }
    if( id == 106 ) {      ret = sys_stat(  bx, cx, dx, si, di );           done = true; }
    if( id == 107 ) {      ret = sys_stat(  bx, cx, dx, si, di );           done = true; }
    if( id == 108 ) {      ret = sys_fstat(  bx, cx, dx, si, di );          done = true; }
    if( id == 114 ) {      ret = sys_wait4(  bx, cx, dx, si, di );          done = true; }
    if( id == 122 ) {      ret = sys_uname(  bx, cx, dx, si, di );          done = true; }
    if( id == 125 ) {      ret = sys_mprotect(  bx, cx, dx, si, di );       done = true; }
    if( id == 126 ) {      ret = sys_sigprocmask(  bx, cx, dx, si, di );    done = true; }
    if( id == 133 ) {      ret = sys_fchdir(  bx, cx, dx, si, di );         done = true; }
    if( id == 136 ) {      ret = sys_personality(  bx, cx, dx, si, di );    done = true; }
    if( id == 141 ) {      ret = sys_getdents(  bx, cx, dx, si, di );       done = true; }
    if( id == 142 ) {      ret = sys_select(  bx, cx, dx, si, di );         done = true; }
    if( id == 143 ) {      ret = sys_flock(  bx, cx, dx, si, di );          done = true; }
    if( id == 146 ) {      ret = sys_writev(  bx, cx, dx, si, di );         done = true; }
    if( id == 162 ) {      ret = sys_nanosleep(  bx, cx, dx, si, di );      done = true; }
    if( id == 163 ) {      ret = sys_mremap(  bx, cx, dx, si, di );         done = true; }
    if( id >= 167 ) {      ret = 0;                                         done = true; }
    if( !done ) {
      process.println( "Emulin Error : Unsupported system call... id=[" + id + "]  ( evals = " + process.evals( ) + ")" );
      sys_exit( 1, 0, 0, 0, 0 );
      ret = 0;
    }
    else {
      if( sysinfo.verbose( )) {
        process.println( "          Syscall ret = " + ret );
      }
    }
    return( ret );
  }
}
