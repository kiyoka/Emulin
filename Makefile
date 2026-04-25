#------------------------------------------------------------
#  Makefile for Emulin 
#------------------------------------------------------------

REL_VER = 0.2.13b
JAVAC = javac -O -deprecation
JAVAH = javah -jni
JAVA = java

SRCS =	emulin/XInstruction.java \
	emulin/RootSysinfo.java \
	emulin/Util.java \
	emulin/XKernel.java \
	emulin/PipeManager.java \
	emulin/Kernel.java \
	emulin/Decoder.java \
	emulin/Instruction.java \
	emulin/XDecoder.java \
	emulin/Decodeinfo.java \
	emulin/Siginfo.java \
	emulin/Signal.java \
	emulin/ProcessInfo.java \
	emulin/Process.java \
	emulin/Emulin.java \
	emulin/LoadUtil.java \
	emulin/Elf.java \
	emulin/Segment.java \
	emulin/Section.java \
	emulin/Memory.java \
	emulin/Cpu.java \
	emulin/Operand.java \
	emulin/Sysinfo.java \
	emulin/EmuSocket.java \
	emulin/FileAccess.java \
	emulin/Fileinfo.java \
	emulin/Syscall.java \
	emulin/Inode.java \
	emulin/Mount.java \
	emulin/RingBuffer.java \
	emulin/SubProcess.java \
	emulin/Version.java \
	emulin/device/StdConsole.java \
	emulin/device/NativeConsole.java \
	emulin/device/Console.java 

OBJS = $(SRCS:.java=.class)

all : $(OBJS)
#	gcj -O6 -o Emulin --main=emulin.Emulin emulin/*.java emulin/device/*.java
	@echo "update done"

elfbin : elfbin.c Makefile
	/usr/local/bin/gcc -L/mnt/home3/home/kiyoka/mydata/Project-Emulin/lib/lib -O0 -g -static -o elfbin elfbin.c 


bench : bench.c Makefile
	/usr/local/bin/gcc -O6 -g -static -o bench bench.c

emulin/Decoder.java : emulin/opecode.dat emulin/mkope Makefile
	emulin/mkope < emulin/opecode.dat > emulin/Decoder.java

emulin/XInstruction.java : emulin/opecode.dat emulin/mkinstid
	emulin/mkinstid < emulin/opecode.dat > emulin/XInstruction.java

%.class : %.java
	$(JAVAC) $*.java

clean :
	/bin/rm -f emulin/*.class
	/bin/rm -f emulin/device/*.class

emulin_n : $(SRCS)
	toba -O -o emulin_n $(SRCS)

release:
	release.script $(REL_VER)

