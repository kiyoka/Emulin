#
#   Makefile for emu_con.dll
#

CC = gcc 
CXX = c++

DEBUG = -g -Wall -O2
CXXFLAGS = $(DEBUG) 
CFLAGS = $(DEBUG) 
CPPFLAGS = -I. -I$(JDK_ROOT)/include -I$(JDK_ROOT)/include/win32

JDK_ROOT = x:/jdk1.2

AS = as
DLLTOOL = dlltool
DLLWRAP = dllwrap

#
# Various targets to build.
#
DLL_NAME = emu_con.dll
DLL_EXP_DEF = emu_con.def

all: $(DLL_NAME)

#
# DLL related variables. These are used when building the DLL. See later.
#

# Some tools require special CPP macros when building a DLL (eg., _DLL etc).
# Here we don't need anything.

DLL_CFLAGS = -DBUILDING_DLL=1 -D_DLL=1 

# The default entry point defined by dllwrap is __cygwin_dll_entry@12 
# defined in libcygwin.a, but that's only appropriate for Cygwin apps,
# but since Java is a MSVC app, we need to provide a different entry
# point. Note the leading underscore and the trailing @12.
# The -s flag strips the DLL to shrink the size.

DLL_LDFLAGS = -Wl,-e,__cygwin_noncygwin_dll_entry@12 -s

# any extra libraries that your DLL may depend on.
DLL_LDLIBS = 

DLL_SRCS  = emu_con.c
DLL_OBJS  = $(DLL_SRCS:.cc=.o)
DLL_OBJS := $(DLL_OBJS:.c=.o)

###
#
# Making DLL
#
###

DLLWRAP_FLAGS = --output-def $(DLL_EXP_DEF) \
	--add-stdcall-alias \
	--driver-name $(CC) \
	$(IMAGE_BASE)

$(DLL_NAME): $(DLL_OBJS)
	$(DLLWRAP) $(DLLWRAP_FLAGS) -o $(DLL_NAME) \
	    $(DLL_OBJS) $(DLL_LDFLAGS) $(DLL_LDLIBS)

#
# dependencies.
#

#
# default rules for building DLL objects. Note that client programs (ie.,
# the ones that *use* the DLL) have to be compiled without the DLL_CFLAGS
# flags.
#
.cc.o:
	$(CXX) -c $(DLL_CFLAGS) $(CPPFLAGS) $(CXXFLAGS) -o $@ $<
.c.o:
	$(CC) -c $(DLL_CFLAGS) $(CPPFLAGS) $(CFLAGS) -o $@ $<

# Note that we omit the $(DLL_CFLAGS) for client programs.
usedll.o: %o: %c
	$(CC) -c $(CPPFLAGS) $(CFLAGS) -o $@ $<

clean:
	-rm -f $(OBJS) $(DLL_OBJS) $(DLL_NAME) $(DLL_EXP_LIB) $(DLL_EXP_DEF) $(TESTPROGS) test.exe

mkhead:
	javah -jni emulin.device.NativeConsole

test.exe: emu_con.c
	gcc -o test -DDEBUG emu_con.c
