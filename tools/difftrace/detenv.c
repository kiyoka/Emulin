#include <sys/types.h>
#include <unistd.h>
pid_t getpid(void){ return 2; }
pid_t getppid(void){ return 1; }
