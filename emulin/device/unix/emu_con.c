// ----------------------------------------
//  JNI Console Liblary for Unix 
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 2000/01/13 15:51:03 $ 
//  $Id: emu_con.c,v 1.8 2000/01/13 15:51:03 kiyoka Exp $
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


#if 0
// c_lflag bits 
#define ISIG	0000001
#define ICANON	0000002
#define XCASE	0000004
#define ECHO	0000010
#define ECHOE	0000020
#define ECHOK	0000040
#define ECHONL	0000100
#define NOFLSH	0000200
#define TOSTOP	0000400
#define ECHOCTL	0001000
#define ECHOPRT	0002000
#define ECHOKE	0004000
#define FLUSHO	0010000
#define PENDIN	0040000
#define IEXTEN	0100000
#endif


void setting( void )
{
  struct termios p;
  tcgetattr( STDIN_FILENO, &p );
  p.c_cc[VMIN]=1;
  p.c_lflag &= ~ICANON;
  p.c_lflag &= ~ECHO;
  tcsetattr(STDIN_FILENO, TCSAFLUSH, &p );
}

#ifndef DEBUG

// ioctl で、リアルタイムキー入力を行なう
JNIEXPORT jint JNICALL Java_emulin_device_NativeConsole_native_1read(JNIEnv *jenv, jobject jo)
{
  int ch;
  ch = fgetc( stdin );
//  printf( " fgetc = %c [%02X]\n", (char)ch, ch & 0xFF );
  if( -1 == ch ) {
    ch = 0;
  }
//  printf( " fgetc = %c [%02X]\n", (char)ch, ch & 0xFF );
  return( ch );
}

int int_flag = 0;
void interrupt( int num ) {
    //    printf( "interrupt!!!\n" );
    int_flag++;
}

JNIEXPORT jint JNICALL Java_emulin_device_NativeConsole_native_1init(JNIEnv *jenv, jobject jo)
{
  int i;
  signal( SIGINT, interrupt );
  //  setting( );
  return( 0 );
}

JNIEXPORT jint JNICALL Java_emulin_device_NativeConsole_native_1set_1parameter(JNIEnv *jenv, jobject jo, jint c_lflag, jbyte c_cc )
{
  int res;
  struct termios p;
  tcgetattr( STDIN_FILENO, &p );
  p.c_cc[VMIN]=c_cc;
  p.c_lflag = c_lflag;
  res = tcsetattr(STDIN_FILENO, TCSANOW, &p );
  //  printf( " console.c : %d = set_parameter  c_cc = %02X  c_lflag = %04X\n", res, (int)c_cc & 0xFF, c_lflag );
}

JNIEXPORT jint JNICALL Java_emulin_device_NativeConsole_native_1israw(JNIEnv *jenv, jobject jo)
{
  struct termios p;
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

#else

void interrupt( int num ) {
    printf( "interrupt %d\n", num );
}

int main( void )
{
  struct termios p;
  int i, ch;
  tcgetattr( STDIN_FILENO, &p );
  printf( "original c_lflag = %04X\n", p.c_lflag );
  setting( );

  {
      signal( SIGINT, interrupt );
  }

  for( i = 0 ; i < 3 ; i++ ) {
    ch = fgetc( stdin );
    printf( " fgetc = %c\n", (char)ch );
  }
  printf( "VMIN = %d\n", VMIN );
  return( 0 );
}

#endif

