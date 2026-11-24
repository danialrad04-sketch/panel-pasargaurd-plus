#include "TunnelSocket.h"
#include <arpa/inet.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <string.h>
#include <sys/socket.h>

int32_t PasarGuardTunnelDescriptor(const char *ipv4Address) {
    struct in_addr target;
    if (inet_pton(AF_INET, ipv4Address, &target) != 1) return -1;

    struct ifaddrs *interfaces = NULL;
    if (getifaddrs(&interfaces) != 0) return -1;
    char interfaceName[IF_NAMESIZE] = {0};
    for (struct ifaddrs *item = interfaces; item; item = item->ifa_next) {
        if (!item->ifa_addr || item->ifa_addr->sa_family != AF_INET) continue;
        if (strncmp(item->ifa_name, "utun", 4) != 0) continue;
        struct sockaddr_in *address = (struct sockaddr_in *)item->ifa_addr;
        if (address->sin_addr.s_addr != target.s_addr) continue;
        strlcpy(interfaceName, item->ifa_name, sizeof(interfaceName));
        break;
    }
    freeifaddrs(interfaces);
    if (!interfaceName[0]) return -1;

    // Kernel-control socket level and utun interface name option on Darwin.
    const int kernelControl = 2;
    const int utunInterfaceName = 2;
    for (int32_t fd = 0; fd < 1024; fd++) {
        char name[IF_NAMESIZE] = {0};
        socklen_t size = sizeof(name);
        if (getsockopt(fd, kernelControl, utunInterfaceName, name, &size) != 0) continue;
        if (strncmp(name, interfaceName, sizeof(name)) == 0) return fd;
    }
    return -1;
}
