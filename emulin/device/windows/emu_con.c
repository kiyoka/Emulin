// ----------------------------------------
//  JNI Console Liblary for Windows 9x/NT
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 2000/01/13 15:51:12 $ 
//  $Id: emu_con.c,v 1.10 2000/01/13 15:51:12 kiyoka Exp $
// ----------------------------------------

#include <stdio.h>
#include <stdlib.h>
#include <signal.h>
#include <termios.h>
#include <sys/ioctl.h>
#ifndef DEBUG
#include "emulin_device_NativeConsole.h"
#endif

#define STDIN_FILENO 0


// c_lflag bits 
#define LINUX_ISIG	0000001
#define LINUX_ICANON	0000002
#define LINUX_XCASE	0000004
#define LINUX_ECHO	0000010
#define LINUX_ECHOE	0000020
#define LINUX_ECHOK	0000040
#define LINUX_ECHONL	0000100
#define LINUX_NOFLSH	0000200
#define LINUX_TOSTOP	0000400
#define LINUX_ECHOCTL	0001000
#define LINUX_ECHOPRT	0002000
#define LINUX_ECHOKE	0004000
#define LINUX_FLUSHO	0010000
#define LINUX_PENDIN	0040000
#define LINUX_IEXTEN	0100000

int int_flag   = 0;
int canon_flag = 1;
void interrupt( int num ) {
    //    printf( "interrupt!!!\n" );
    int_flag++;
}

#ifndef DEBUG

JNIEXPORT jint JNICALL Java_emulin_device_NativeConsole_native_1init(JNIEnv *jenv, jobject jo)
{
#if 0
  int res = 0;
  struct termios p;
  tcgetattr( STDIN_FILENO, &p );
  p.c_lflag &= ~ISIG;
  res = tcsetattr(STDIN_FILENO, TCSANOW, &p );
  printf( " ISIG    == %04X\n", ISIG );
  printf( " ICANON  == %04X\n", ICANON );
  printf( " ECHO    == %04X\n", ECHO );
  printf( " ECHOE   == %04X\n", ECHOE );
  printf( " console.c : %d = set_parameter  c_cc = %02X  c_lflag = %04X\n", res, (int)p.c_cc[VMIN] & 0xFF, p.c_lflag );
#endif
  //  setting( );
  return( 0 );
}

// ioctl で、リアルタイムキー入力を行なう
JNIEXPORT jint JNICALL Java_emulin_device_NativeConsole_native_1read(JNIEnv *jenv, jobject jo)
{
  int len, ch;
  char buf[1];

  len = read( STDIN_FILENO, buf, 1 );
  ch = (int)buf[0] & 0xFF;
  if( ch == 0xD ) { ch = 0xA; }
  if( 0 == len ) { ch = -1; }
  //  printf( " fgetc = %c [%02X] len = %d\n", (char)ch, ch & 0xFF, len );
  return( ch );
}

JNIEXPORT jint JNICALL Java_emulin_device_NativeConsole_native_1set_1parameter(JNIEnv *jenv, jobject jo, jint c_lflag, jbyte c_cc )
{
  int res;
  struct termios p;
  //  printf( "native_set_parameter\n" );
  tcgetattr( STDIN_FILENO, &p );
  //  p.c_cc[VMIN]=c_cc;
  p.c_cc[VMIN]=1;
  p.c_cc[VTIME]=0;
  p.c_lflag = 0;
  canon_flag = 0;
  if( c_lflag & LINUX_ISIG   ) { p.c_lflag |= ISIG;   }
  if( c_lflag & LINUX_ICANON ) { p.c_lflag |= ICANON; canon_flag = 1; }
  if( c_lflag & LINUX_ECHO   ) { p.c_lflag |= ECHO;   }
  res = tcsetattr(STDIN_FILENO, TCSANOW, &p );
#if 0
  printf( "[set_parameter]\n" );
  printf( " ISIG    == %04X\n", ISIG );
  printf( " ICANON  == %04X\n", ICANON );
  printf( " ECHO    == %04X\n", ECHO );
  printf( " ECHOE   == %04X\n", ECHOE );
  printf( " console.c : %d = set_parameter  c_cc = %02X  linux c_lflag = %04X  c_lflag = %04X\n", res, (int)c_cc & 0xFF, c_lflag, p.c_lflag );
#endif
  return( 0 );
}

JNIEXPORT jint JNICALL Java_emulin_device_NativeConsole_native_1israw(JNIEnv *jenv, jobject jo)
{
  struct termios p;
  // printf( "native_1israw\n" );
  tcgetattr( STDIN_FILENO, &p );
  return( 0 == (p.c_lflag & ICANON));
}

JNIEXPORT jint JNICALL Java_emulin_device_NativeConsole_native_1check_1int(JNIEnv *jenv, jobject jo)
{
  int ret = int_flag;
  return( ret );
}

JNIEXPORT jint JNICALL Java_emulin_device_NativeConsole_native_1cancel_1int(JNIEnv *jenv, jobject jo)
{
  int_flag = 0;
  return( 0 );
}

JNIEXPORT jint JNICALL Java_emulin_device_NativeConsole_native_1set_1int(JNIEnv *jenv, jobject jo, jint sig)
{
  interrupt( sig );
  return( 0 );
}

#include <windows.h>

int WINAPI
rdll_init(HANDLE h, DWORD reason, void *foo)
{
  return 1;
}

int main( void )
{
}

#else

int main( void )
{
  void setting( void );
  struct termios p;
  int i, ch;
  char buf[1];
  printf( "start\n" );
  tcgetattr( STDIN_FILENO, &p );
  printf( "original c_lflag = %04X\n", p.c_lflag );
  signal( SIGINT, SIG_IGN );
  setting( );
  for( i = 0 ; i < 3 ; i++ ) {
    ch = read( STDIN_FILENO, buf, 1 );
    printf( " fgetc = %c[%02X]\n", (char)buf[0], (int)buf[0] & 0xFF );
    ch = buf[0];
    if( ch == 0x8 ) {
      printf( "aa" );
    }
    if( ch == 0x3 ) {
      printf( "interrupt!!!" );
    }
    if( ch == 0x4 ) {
      printf( "EOF" );
    }
    printf( "\n" );
  }
  printf( "VMIN = %d\n", VMIN );
  return( 0 );
}

#endif


void setting( void )
{
  struct termios p;
  tcgetattr( STDIN_FILENO, &p );
  p.c_cc[VMIN]=0;
  p.c_cc[VTIME]=0;
  p.c_cc[VINTR]='c';
  p.c_lflag &= ~ICANON;
  p.c_lflag &= ~ECHO;
  /*  p.c_lflag &= ~ISIG; */
  tcsetattr(STDIN_FILENO, TCSAFLUSH, &p );
}

void test_print( void )
{
  printf( " test_print( ) done\n" );
}

