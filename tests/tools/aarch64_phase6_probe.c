#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

static void fail(const char *what) {
    fprintf(stderr, "%s: %s\n", what, strerror(errno));
    exit(1);
}

static void require(int condition, const char *what) {
    if (!condition) {
        fprintf(stderr, "%s\n", what);
        exit(1);
    }
}

static void test_time_pipe(void) {
    struct timespec real, mono, timeout = { .tv_sec = 1 };
    int pipefd[2];
    struct pollfd pollfd;
    char byte = 0;

    if (clock_gettime(CLOCK_REALTIME, &real) != 0) fail("clock_gettime realtime");
    if (clock_gettime(CLOCK_MONOTONIC, &mono) != 0) fail("clock_gettime monotonic");
    require(real.tv_sec > 0 && mono.tv_sec >= 0, "clock values invalid");
    if (pipe2(pipefd, O_CLOEXEC | O_NONBLOCK) != 0) fail("pipe2");
    if (write(pipefd[1], "p", 1) != 1) fail("pipe write");
    pollfd.fd = pipefd[0];
    pollfd.events = POLLIN;
    pollfd.revents = 0;
    if (ppoll(&pollfd, 1, &timeout, NULL) != 1) fail("ppoll pipe");
    require((pollfd.revents & POLLIN) != 0, "pipe not readable");
    if (read(pipefd[0], &byte, 1) != 1 || byte != 'p') fail("pipe read");
    close(pipefd[0]);
    close(pipefd[1]);
    puts("clock-pipe-ppoll-ok");
}

static void test_socketpair(void) {
    int pair[2];
    char first[3] = {0}, second[3] = {0};
    struct iovec outv[2] = {
        { .iov_base = (void *)"ab", .iov_len = 2 },
        { .iov_base = (void *)"cd", .iov_len = 2 }
    };
    struct iovec inv[2] = {
        { .iov_base = first, .iov_len = 2 },
        { .iov_base = second, .iov_len = 2 }
    };
    struct msghdr out = { .msg_iov = outv, .msg_iovlen = 2 };
    struct msghdr in = { .msg_iov = inv, .msg_iovlen = 2 };

    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, pair) != 0) fail("socketpair");
    if (sendmsg(pair[0], &out, 0) != 4) fail("sendmsg socketpair");
    if (recvmsg(pair[1], &in, 0) != 4) fail("recvmsg socketpair");
    require(memcmp(first, "ab", 2) == 0 && memcmp(second, "cd", 2) == 0,
            "socketpair payload mismatch");
    close(pair[0]);
    close(pair[1]);
    puts("socketpair-sendmsg-recvmsg-ok");
}

static void test_udp(void) {
    int server, client;
    struct sockaddr_in address = { .sin_family = AF_INET };
    struct sockaddr_in peer = {0};
    socklen_t length = sizeof(address), peer_length = sizeof(peer);
    struct pollfd pollfd;
    struct timespec timeout = { .tv_sec = 1 };
    char buffer[8] = {0};

    server = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    client = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (server < 0 || client < 0) fail("udp socket");
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(server, (struct sockaddr *)&address, sizeof(address)) != 0) fail("udp bind");
    if (getsockname(server, (struct sockaddr *)&address, &length) != 0) fail("udp getsockname");
    require(address.sin_port != 0, "udp ephemeral port missing");
    if (sendto(client, "udp", 3, 0, (struct sockaddr *)&address, sizeof(address)) != 3)
        fail("udp sendto");
    pollfd.fd = server;
    pollfd.events = POLLIN;
    pollfd.revents = 0;
    if (ppoll(&pollfd, 1, &timeout, NULL) != 1) fail("udp ppoll");
    if (recvfrom(server, buffer, sizeof(buffer), 0,
                 (struct sockaddr *)&peer, &peer_length) != 3) fail("udp recvfrom");
    require(memcmp(buffer, "udp", 3) == 0 && peer.sin_family == AF_INET,
            "udp payload mismatch");
    close(server);
    close(client);
    puts("udp-sendto-recvfrom-ok");
}

static void test_tcp(void) {
    int listener, client, accepted, type = 0, error = -1, one = 1;
    socklen_t length, option_length;
    struct sockaddr_in address = { .sin_family = AF_INET };
    char buffer[8] = {0};

    listener = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (listener < 0) fail("tcp listener socket");
    if (setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one)) != 0)
        fail("tcp setsockopt");
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(listener, (struct sockaddr *)&address, sizeof(address)) != 0) fail("tcp bind");
    if (listen(listener, 4) != 0) fail("tcp listen");
    length = sizeof(address);
    if (getsockname(listener, (struct sockaddr *)&address, &length) != 0)
        fail("tcp getsockname");
    client = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (client < 0) fail("tcp client socket");
    if (connect(client, (struct sockaddr *)&address, sizeof(address)) != 0) fail("tcp connect");
    accepted = accept4(listener, NULL, NULL, SOCK_CLOEXEC);
    if (accepted < 0) fail("tcp accept4");
    option_length = sizeof(type);
    if (getsockopt(client, SOL_SOCKET, SO_TYPE, &type, &option_length) != 0)
        fail("tcp getsockopt type");
    require(type == SOCK_STREAM && option_length == sizeof(type), "tcp type mismatch");
    option_length = sizeof(error);
    if (getsockopt(client, SOL_SOCKET, SO_ERROR, &error, &option_length) != 0)
        fail("tcp getsockopt error");
    require(error == 0, "tcp SO_ERROR nonzero");
    if (write(client, "tcp", 3) != 3) fail("tcp write");
    if (read(accepted, buffer, sizeof(buffer)) != 3) fail("tcp read");
    require(memcmp(buffer, "tcp", 3) == 0, "tcp payload mismatch");
    if (shutdown(client, SHUT_WR) != 0) fail("tcp shutdown");
    close(accepted);
    close(client);
    close(listener);
    puts("tcp-connect-accept-ok");
}

static void test_pty(void) {
    int master, slave, number = -1, unlocked = 0;
    char path[64];
    struct termios terminal;

    master = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) fail("open ptmx");
    if (ioctl(master, TIOCGPTN, &number) != 0 || number < 0) fail("TIOCGPTN");
    if (ioctl(master, TIOCSPTLCK, &unlocked) != 0) fail("TIOCSPTLCK");
    snprintf(path, sizeof(path), "/dev/pts/%d", number);
    slave = open(path, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (slave < 0) fail("open pts slave");
    if (tcgetattr(slave, &terminal) != 0) fail("tcgetattr pts");
    close(slave);
    close(master);
    puts("posix-pty-ok");
}

static void test_dns(void) {
    struct addrinfo hints = {0}, *result = NULL, *entry;
    struct sockaddr_in6 destination = { .sin6_family = AF_INET6 };
    struct sockaddr_in6 local = {0};
    socklen_t local_length = sizeof(local);
    int source_socket;
    int status, found = 0;

    source_socket = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (source_socket < 0) fail("dns source socket");
    destination.sin6_port = htons(9);
    destination.sin6_addr.s6_addr[10] = 0xff;
    destination.sin6_addr.s6_addr[11] = 0xff;
    destination.sin6_addr.s6_addr[12] = 127;
    destination.sin6_addr.s6_addr[15] = 1;
    if (connect(source_socket, (struct sockaddr *)&destination,
                sizeof(destination)) != 0) fail("dns source connect");
    if (getsockname(source_socket, (struct sockaddr *)&local,
                    &local_length) != 0) fail("dns source getsockname");
    require(local.sin6_family == AF_INET6
            && IN6_IS_ADDR_V4MAPPED(&local.sin6_addr),
            "dns source address is not IPv4-mapped IPv6");
    close(source_socket);
    puts("dns-v4mapped-source-ok");

    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    status = getaddrinfo("deb.debian.org", "443", &hints, &result);
    if (status != 0) {
        fprintf(stderr, "getaddrinfo: %s\n", gai_strerror(status));
        exit(1);
    }
    for (entry = result; entry != NULL; entry = entry->ai_next) {
        if (entry->ai_family == AF_INET || entry->ai_family == AF_INET6) found = 1;
    }
    freeaddrinfo(result);
    require(found, "DNS returned no internet address");
    puts("dns-getaddrinfo-ok");
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "dns") == 0) {
        test_dns();
        return 0;
    }
    test_time_pipe();
    test_socketpair();
    test_udp();
    test_tcp();
    test_pty();
    puts("aarch64-phase6-local-ok");
    return 0;
}
