

libemu_con.so : emu_con.c
	javah -jni emulin.device.NativeConsole
	gcc -I /usr/local/java/include -I /usr/local/java/include/genunix  -o libemu_con.so -shared emu_con.c

test : emu_con.c
	gcc -o test -DDEBUG emu_con.c

