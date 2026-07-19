package emulin;

import java.math.BigInteger;

// 80-bit extended precision softfloat (issue #757)。
//
// i386 x87 の「真の 80-bit」= 64-bit 仮数・15-bit 指数を値型 Val で表現し、
// 演算は CW の PC (仮数 24/53/64) と RC を丸めで反映する。BigInteger ベースの
// 正確性優先実装 (性能はホットパスと判明したら後日最適化)。
//
// Val の表現:
//   - FIN: sig==0 はゼロ (exp 不問)。非ゼロは bit63=1 に正規化し
//     value = sig * 2^(exp-63)。exp は非バイアスの int (内部では ext レンジ外も
//     一時的に保持し得るが、演算結果は roundPack が ±16383 に clamp する。
//     exp < -16382 は「ext denormal 域」で、roundPack が有効精度を落として
//     gradual に丸め、toExt が bexp=0 形式へ denormal 化する)。
//   - NAN: sig は ext 形式の仮数そのもの (bit63=1、bit62=quiet、下位 payload)。
public final class X87 {

  public static final int RN = 0, RD = 1, RU = 2, RZ = 3;
  static final int FIN = 0, INF = 1, NAN = 2;

  private static final BigInteger M64B = BigInteger.ONE.shiftLeft( 64 ).subtract( BigInteger.ONE );

  public static final class Val {
    final int kind, sign, exp;
    final long sig;
    Val( int kind, int sign, int exp, long sig ) {
      this.kind = kind; this.sign = sign; this.exp = exp; this.sig = sig;
    }
  }

  static final Val PZERO = new Val( FIN, 0, 0, 0 );
  static final Val NZERO = new Val( FIN, 1, 0, 0 );
  static final Val PINF = new Val( INF, 0, 0, 0x8000000000000000L );
  static final Val NINF = new Val( INF, 1, 0, 0x8000000000000000L );

  static Val zero( int sign ) { return sign == 1 ? NZERO : PZERO; }
  static Val inf( int sign )  { return sign == 1 ? NINF : PINF; }
  static Val indefinite( )    { return new Val( NAN, 1, 0, 0xC000000000000000L ); }

  static boolean isNan( Val v )  { return v.kind == NAN; }
  static boolean isInf( Val v )  { return v.kind == INF; }
  static boolean isZero( Val v ) { return v.kind == FIN && v.sig == 0; }
  // ext denormal 域 (bexp=0 で格納される値)。
  static boolean isDenormalExt( Val v ) { return v.kind == FIN && v.sig != 0 && v.exp < -16382; }

  static Val withSign( Val v, int sign ) { return new Val( v.kind, sign, v.exp, v.sig ); }
  static Val negate( Val v ) { return withSign( v, v.sign ^ 1 ); }
  static Val abs( Val v )    { return withSign( v, 0 ); }

  private static BigInteger usig( long s ) { return BigInteger.valueOf( s ).and( M64B ); }
  static BigInteger usigB( long s ) { return usig( s ); }   // Cpu (FPREM 等) 用

  // ---- 丸めコア --------------------------------------------------------------
  // 正確値 (-1)^sign * mant * 2^exp2 (mant > 0) を prec bit へ RC 丸めし、
  // ext レンジ (overflow -> ±inf / RZ 系は最大有限、denormal 域 -> 有効精度低減) で Val 化。
  static Val roundPack( int sign, BigInteger mant, int exp2, int prec, int rc ) {
    if( mant.signum( ) == 0 ) return zero( sign );
    int len = mant.bitLength( );
    int eTop = exp2 + len - 1;
    int p = prec;
    if( eTop < -16382 ) {                               // gradual underflow
      p = prec - (-16382 - eTop);
      if( p <= 0 ) {
        // 最小 denormal 単位 (2^(-16382-prec+1)) 未満: RN は半分超のみ切上げ、
        // RU/RD は inexact 方向へ最小単位、RZ は ±0。
        boolean up;
        if( rc == RN )      up = ( p == 0 ) && ( mant.bitCount( ) != 1 );
        else if( rc == RU ) up = sign == 0;
        else if( rc == RD ) up = sign == 1;
        else                up = false;
        if( !up ) return zero( sign );
        return new Val( FIN, sign, -16382 - prec + 1, 0x8000000000000000L );
      }
    }
    int drop = len - p;
    BigInteger keep;
    if( drop <= 0 ) {
      keep = mant.shiftLeft( -drop );
    } else {
      keep = mant.shiftRight( drop );
      BigInteger rem = mant.and( BigInteger.ONE.shiftLeft( drop ).subtract( BigInteger.ONE ) );
      BigInteger half = BigInteger.ONE.shiftLeft( drop - 1 );
      int c = rem.compareTo( half );
      boolean up = false;
      if( rc == RN )      up = c > 0 || ( c == 0 && keep.testBit( 0 ) );
      else if( rc == RU ) up = rem.signum( ) != 0 && sign == 0;
      else if( rc == RD ) up = rem.signum( ) != 0 && sign == 1;
      if( up ) {
        keep = keep.add( BigInteger.ONE );
        if( keep.bitLength( ) > p ) { keep = keep.shiftRight( 1 ); eTop++; }
      }
    }
    if( eTop > 16383 ) {                                // overflow
      boolean toInf = ( rc == RN ) || ( rc == RU && sign == 0 ) || ( rc == RD && sign == 1 );
      if( toInf ) return inf( sign );
      long maxSig = ( prec >= 64 ) ? 0xFFFFFFFFFFFFFFFFL
                                   : ( ( ( 1L << prec ) - 1 ) << ( 64 - prec ) );
      return new Val( FIN, sign, 16383, maxSig );
    }
    long sig = keep.longValue( ) << ( 64 - p );
    return new Val( FIN, sign, eTop, sig );
  }

  // ---- double / float / ext / int との変換 ------------------------------------

  static Val fromDouble( double d ) {
    long b = Double.doubleToRawLongBits( d );
    int sign = (int)( b >>> 63 );
    int e = (int)( ( b >>> 52 ) & 0x7FF );
    long frac = b & 0xFFFFFFFFFFFFFL;
    if( e == 0x7FF ) {
      if( frac == 0 ) return inf( sign );
      return new Val( NAN, sign, 0, 0x8000000000000000L | ( frac << 11 ) );
    }
    if( e == 0 ) {
      if( frac == 0 ) return zero( sign );
      int nlz = Long.numberOfLeadingZeros( frac );
      return new Val( FIN, sign, -1011 - nlz, frac << nlz );
    }
    return new Val( FIN, sign, e - 1023, 0x8000000000000000L | ( frac << 11 ) );
  }

  // FIN 非ゼロを prec/emin の狭い形式へ RC 丸め。long[]{keep, e, isDenormal}。
  //   denormal の keep は単位 2^(emin-prec+1) のカウント。normal の keep は
  //   prec bit (integer bit 込み)。桁上がりで e が増え得る (emax 超過は caller 検査)。
  private static long[] narrow( Val v, int prec, int emin, int rc ) {
    int e = v.exp;
    boolean den = e < emin;
    int p = den ? prec - ( emin - e ) : prec;
    if( p <= 0 ) {
      // 最小 denormal 単位未満: RN は半分超のみ、RU/RD は inexact 方向、RZ は 0。
      boolean up;
      if( rc == RN )      up = ( p == 0 ) && ( v.sig != 0x8000000000000000L );
      else if( rc == RU ) up = v.sign == 0;
      else if( rc == RD ) up = v.sign == 1;
      else                up = false;
      return new long[]{ up ? 1 : 0, emin, 1 };
    }
    int drop = 64 - p;                                  // 0..63
    long keep = v.sig >>> drop;
    boolean up = false;
    if( drop > 0 ) {
      long remTop = v.sig << ( 64 - drop );             // 端数を bit63 詰めで比較
      long HALF = 0x8000000000000000L;
      int c = Long.compareUnsigned( remTop, HALF );
      if( rc == RN )      up = c > 0 || ( c == 0 && ( keep & 1 ) == 1 );
      else if( rc == RU ) up = remTop != 0 && v.sign == 0;
      else if( rc == RD ) up = remTop != 0 && v.sign == 1;
      if( up ) keep++;
    }
    if( den ) {
      // 桁上がりで 2^(prec-1) に達したら最小 normal (assemble 側の normal 経路が
      // frac=0/e=emin として正しく組む)。それ以外は denormal。
      if( keep == ( 1L << ( prec - 1 ) ) ) return new long[]{ keep, emin, 0 };
      return new long[]{ keep, emin, 1 };
    }
    if( ( keep >>> ( prec - 1 ) ) > 1 ) { keep >>>= 1; e++; }   // normal の桁上がり
    return new long[]{ keep, e, 0 };
  }

  static long toDoubleBits( Val v, int rc ) {
    if( v.kind == NAN )
      return ( (long)v.sign << 63 ) | 0x7FF0000000000000L | 0x8000000000000L
           | ( ( v.sig & 0x3FFFFFFFFFFFFFFFL ) >>> 11 );
    if( v.kind == INF ) return ( (long)v.sign << 63 ) | 0x7FF0000000000000L;
    if( v.sig == 0 )    return (long)v.sign << 63;
    if( v.exp > 1023 ) {                                // overflow
      boolean toInf = ( rc == RN ) || ( rc == RU && v.sign == 0 ) || ( rc == RD && v.sign == 1 );
      if( toInf ) return ( (long)v.sign << 63 ) | 0x7FF0000000000000L;
      return ( (long)v.sign << 63 ) | 0x7FEFFFFFFFFFFFFFL;     // 最大有限
    }
    long[] n = narrow( v, 53, -1022, rc );
    long keep = n[0]; int e = (int)n[1];
    if( n[2] == 1 )                                      // denormal (単位 2^-1074)
      return ( (long)v.sign << 63 ) | keep;
    return ( (long)v.sign << 63 ) | ( (long)( e + 1023 ) << 52 ) | ( keep & 0xFFFFFFFFFFFFFL );
  }

  static double toDouble( Val v ) { return Double.longBitsToDouble( toDoubleBits( v, RN ) ); }

  static int toFloatBits( Val v, int rc ) {
    if( v.kind == NAN )
      return ( v.sign << 31 ) | 0x7F800000 | 0x400000
           | (int)( ( v.sig & 0x3FFFFFFFFFFFFFFFL ) >>> 40 );
    if( v.kind == INF ) return ( v.sign << 31 ) | 0x7F800000;
    if( v.sig == 0 )    return v.sign << 31;
    if( v.exp > 127 ) {
      boolean toInf = ( rc == RN ) || ( rc == RU && v.sign == 0 ) || ( rc == RD && v.sign == 1 );
      if( toInf ) return ( v.sign << 31 ) | 0x7F800000;
      return ( v.sign << 31 ) | 0x7F7FFFFF;
    }
    long[] n = narrow( v, 24, -126, rc );
    long keep = n[0]; int e = (int)n[1];
    if( n[2] == 1 )
      return ( v.sign << 31 ) | (int)keep;
    return ( v.sign << 31 ) | ( ( e + 127 ) << 23 ) | (int)( keep & 0x7FFFFF );
  }

  // FLD m80 (bit copy)。denormal/pseudo/unnormal は正規化して値として保持。
  static Val fromExt( long sig, int se ) {
    int sign = ( se >> 15 ) & 1;
    int bexp = se & 0x7FFF;
    if( bexp == 0x7FFF ) {
      if( sig == 0x8000000000000000L ) return inf( sign );
      return new Val( NAN, sign, 0, sig | 0x8000000000000000L );
    }
    if( sig == 0 ) return zero( sign );
    int nlz = Long.numberOfLeadingZeros( sig );
    if( bexp == 0 )                                     // (pseudo-)denormal
      return new Val( FIN, sign, -16382 - nlz, sig << nlz );
    return new Val( FIN, sign, ( bexp - 16383 ) - nlz, sig << nlz );
  }

  // FSTP m80。戻り値 {sig64, se16}。
  static long[] toExt( Val v ) {
    if( v.kind == NAN ) return new long[]{ v.sig, ( v.sign << 15 ) | 0x7FFF };
    if( v.kind == INF ) return new long[]{ 0x8000000000000000L, ( v.sign << 15 ) | 0x7FFF };
    if( v.sig == 0 )    return new long[]{ 0, v.sign << 15 };
    if( v.exp < -16382 ) {                              // denormal 化 (bexp=0)
      int sh = -16382 - v.exp;
      long s = ( sh > 63 ) ? 0 : ( v.sig >>> sh );
      return new long[]{ s, v.sign << 15 };
    }
    if( v.exp > 16383 )                                 // 防御 (演算側で clamp 済のはず)
      return new long[]{ 0x8000000000000000L, ( v.sign << 15 ) | 0x7FFF };
    return new long[]{ v.sig, ( v.sign << 15 ) | ( v.exp + 16383 ) };
  }

  static Val fromLong( long v ) {
    if( v == 0 ) return PZERO;
    int sign = 0;
    long mag = v;
    if( v < 0 ) { sign = 1; mag = -v; }
    if( mag == Long.MIN_VALUE ) return new Val( FIN, 1, 63, 0x8000000000000000L );  // -2^63
    int nlz = Long.numberOfLeadingZeros( mag );
    return new Val( FIN, sign, 63 - nlz, mag << nlz );
  }

  // RC で整数へ丸めた long。NaN/Inf/範囲外は Long.MIN_VALUE (integer indefinite と同値)。
  static long toLongRounded( Val v, int rc ) {
    if( v.kind != FIN ) return Long.MIN_VALUE;
    if( v.sig == 0 ) return 0;
    int e = v.exp;
    if( e >= 63 ) {
      if( e == 63 && v.sign == 1 && v.sig == 0x8000000000000000L ) return Long.MIN_VALUE; // -2^63
      return Long.MIN_VALUE;
    }
    int drop = 63 - e;
    if( drop >= 64 ) {                                  // |v| < 1
      boolean up;
      if( rc == RN )      up = ( drop == 64 ) && ( v.sig != 0x8000000000000000L ); // (0.5,1)
      else if( rc == RU ) up = v.sign == 0;
      else if( rc == RD ) up = v.sign == 1;
      else                up = false;
      long r = up ? 1 : 0;
      return v.sign == 1 ? -r : r;
    }
    long keep = v.sig >>> drop;
    long rem = v.sig & ( ( 1L << drop ) - 1 );
    long half = 1L << ( drop - 1 );
    boolean up = false;
    if( rc == RN )      up = Long.compareUnsigned( rem, half ) > 0
                          || ( rem == half && ( keep & 1 ) == 1 );
    else if( rc == RU ) up = rem != 0 && v.sign == 0;
    else if( rc == RD ) up = rem != 0 && v.sign == 1;
    if( up ) keep++;
    if( keep < 0 ) return Long.MIN_VALUE;               // 2^63 到達 (正なら範囲外、負なら -2^63)
    return v.sign == 1 ? -keep : keep;
  }

  // FRNDINT: RC で整数化 (値は Val のまま、レンジ不変)。
  static Val roundToInt( Val v, int rc ) {
    if( v.kind != FIN || v.sig == 0 || v.exp >= 63 ) return v;
    if( v.exp < -1 ) {                                  // |v| < 0.5
      boolean up = ( rc == RU && v.sign == 0 ) || ( rc == RD && v.sign == 1 );
      return up ? new Val( FIN, v.sign, 0, 0x8000000000000000L ) : zero( v.sign );
    }
    long r = toLongRounded( v, rc );
    long mag = Math.abs( r );
    if( r == 0 ) return zero( v.sign );
    if( mag == Long.MIN_VALUE )                          // 2^63 へ丸め上がり
      return new Val( FIN, v.sign, 63, 0x8000000000000000L );
    int nlz = Long.numberOfLeadingZeros( mag );
    return new Val( FIN, v.sign, 63 - nlz, mag << nlz );
  }

  // ---- NaN 伝播 ---------------------------------------------------------------
  static Val propNan( Val a, Val b ) {
    Val n;
    if( a.kind == NAN && b.kind == NAN )
      n = ( Long.compareUnsigned( a.sig & 0x3FFFFFFFFFFFFFFFL,
                                  b.sig & 0x3FFFFFFFFFFFFFFFL ) >= 0 ) ? a : b;
    else n = ( a.kind == NAN ) ? a : b;
    return new Val( NAN, n.sign, 0, n.sig | 0x4000000000000000L );   // quiet 化
  }

  // ---- 算術 (prec = 24/53/64, rc = RC) ----------------------------------------

  static Val add( Val a, Val b, int prec, int rc ) {
    if( a.kind == NAN || b.kind == NAN ) return propNan( a, b );
    if( a.kind == INF || b.kind == INF ) {
      if( a.kind == INF && b.kind == INF )
        return ( a.sign == b.sign ) ? a : indefinite( );
      return a.kind == INF ? a : b;
    }
    if( a.sig == 0 && b.sig == 0 )
      return ( a.sign == b.sign ) ? a : zero( rc == RD ? 1 : 0 );
    int ea = a.exp - 63, eb = b.exp - 63;
    int e = Math.min( ea, eb );
    BigInteger sa = usig( a.sig ).shiftLeft( ea - e );
    BigInteger sb = usig( b.sig ).shiftLeft( eb - e );
    if( a.sign == 1 ) sa = sa.negate( );
    if( b.sign == 1 ) sb = sb.negate( );
    BigInteger s = sa.add( sb );
    if( s.signum( ) == 0 ) return zero( rc == RD ? 1 : 0 );
    return roundPack( s.signum( ) < 0 ? 1 : 0, s.abs( ), e, prec, rc );
  }

  static Val sub( Val a, Val b, int prec, int rc ) {
    return add( a, negate( b ), prec, rc );
  }

  static Val mul( Val a, Val b, int prec, int rc ) {
    if( a.kind == NAN || b.kind == NAN ) return propNan( a, b );
    int sign = a.sign ^ b.sign;
    if( a.kind == INF || b.kind == INF ) {
      if( isZero( a ) || isZero( b ) ) return indefinite( );        // 0 * inf
      return inf( sign );
    }
    if( a.sig == 0 || b.sig == 0 ) return zero( sign );
    return roundPack( sign, usig( a.sig ).multiply( usig( b.sig ) ),
                      ( a.exp - 63 ) + ( b.exp - 63 ), prec, rc );
  }

  static Val div( Val a, Val b, int prec, int rc ) {
    if( a.kind == NAN || b.kind == NAN ) return propNan( a, b );
    int sign = a.sign ^ b.sign;
    if( a.kind == INF ) {
      if( b.kind == INF ) return indefinite( );                     // inf / inf
      return inf( sign );
    }
    if( b.kind == INF ) return zero( sign );
    if( b.sig == 0 ) {
      if( a.sig == 0 ) return indefinite( );                        // 0 / 0
      return inf( sign );                                           // #Z (masked)
    }
    if( a.sig == 0 ) return zero( sign );
    int sh = prec + 3;
    BigInteger[] qr = usig( a.sig ).shiftLeft( sh ).divideAndRemainder( usig( b.sig ) );
    BigInteger mant = qr[0].shiftLeft( 1 ).or(
        qr[1].signum( ) != 0 ? BigInteger.ONE : BigInteger.ZERO );
    return roundPack( sign, mant, ( a.exp - 63 ) - sh - ( b.exp - 63 ) - 1, prec, rc );
  }

  static Val sqrt( Val a, int prec, int rc ) {
    if( a.kind == NAN ) return propNan( a, a );
    if( isZero( a ) ) return a;                                     // sqrt(±0) = ±0
    if( a.sign == 1 ) return indefinite( );                         // 負 -> #IA
    if( a.kind == INF ) return PINF;
    int e = a.exp - 63;
    int t = Math.max( 0, 2 * prec + 4 - 64 );
    if( ( ( e - t ) & 1 ) != 0 ) t++;
    BigInteger big = usig( a.sig ).shiftLeft( t );
    BigInteger s = big.sqrt( );
    boolean sticky = !s.multiply( s ).equals( big );
    BigInteger mant = s.shiftLeft( 1 ).or( sticky ? BigInteger.ONE : BigInteger.ZERO );
    return roundPack( 0, mant, ( e - t ) / 2 - 1, prec, rc );
  }

  // FSCALE: a * 2^trunc(b)。b の trunc は RZ 整数化 + clamp。
  static Val scale( Val a, Val b, int prec, int rc ) {
    if( a.kind == NAN || b.kind == NAN ) return propNan( a, b );
    if( a.kind == INF ) {
      if( b.kind == INF && b.sign == 1 ) return indefinite( );      // inf * 2^-inf
      return a;
    }
    if( a.sig == 0 && a.kind == FIN ) {
      if( b.kind == INF && b.sign == 0 ) return indefinite( );      // 0 * 2^+inf
      return a;
    }
    if( b.kind == INF )
      return ( b.sign == 0 ) ? inf( a.sign ) : zero( a.sign );
    long n;
    if( b.kind == FIN && b.sig != 0 && b.exp >= 31 ) n = ( b.sign == 1 ) ? -100000 : 100000;
    else {
      n = toLongRounded( b, RZ );
      if( n > 100000 ) n = 100000;
      if( n < -100000 ) n = -100000;
    }
    return roundPack( a.sign, usig( a.sig ), (int)( ( a.exp - 63 ) + n ), prec, rc );
  }

  // ---- 比較 -------------------------------------------------------------------
  // 戻り値: -2 = unordered / -1 / 0 / +1。
  static int compare( Val a, Val b ) {
    if( a.kind == NAN || b.kind == NAN ) return -2;
    boolean az = isZero( a ), bz = isZero( b );
    if( az && bz ) return 0;                                        // +0 == -0
    if( az ) return b.sign == 1 ? 1 : -1;
    if( bz ) return a.sign == 1 ? -1 : 1;
    if( a.sign != b.sign ) return a.sign == 1 ? -1 : 1;
    int magCmp;
    if( a.kind == INF || b.kind == INF ) {
      magCmp = ( a.kind == INF && b.kind == INF ) ? 0 : ( a.kind == INF ? 1 : -1 );
    } else if( a.exp != b.exp ) {
      magCmp = ( a.exp > b.exp ) ? 1 : -1;
    } else {
      magCmp = Long.compareUnsigned( a.sig, b.sig );
      if( magCmp != 0 ) magCmp = magCmp > 0 ? 1 : -1;
    }
    return ( a.sign == 1 ) ? -magCmp : magCmp;
  }
}
