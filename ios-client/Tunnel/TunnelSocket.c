#include "TunnelSocket.h"
#include <arpa/inet.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/kern_control.h>
#include <sys/socket.h>
#include <sys/sys_domain.h>

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

    // Locate the utun socket associated with the settings installed by Network Extension.
    struct ctl_info control = {0};
    strlcpy(control.ctl_name, "com.apple.net.utun_control", sizeof(control.ctl_name));
    for (int32_t fd = 0; fd < 1024; fd++) {
        struct sockaddr_ctl peer = {0};
        socklen_t peerSize = sizeof(peer);
        if (getpeername(fd, (struct sockaddr *)&peer, &peerSize) != 0) continue;
        if (peerSize != sizeof(peer) || peer.sc_family != AF_SYSTEM || peer.ss_sysaddr != AF_SYS_CONTROL) continue;
        if (!control.ctl_id && ioctl(fd, CTLIOCGINFO, &control) != 0) continue;
        if (peer.sc_id != control.ctl_id) continue;

        // UTUN_OPT_IFNAME (2) is the kernel-control socket option for the utun name.
        char name[IF_NAMESIZE] = {0};
        socklen_t size = sizeof(name);
        if (getsockopt(fd, SYSPROTO_CONTROL, 2, name, &size) != 0) continue;
        if (strncmp(name, interfaceName, sizeof(name)) == 0) return fd;
    }
    return -1;
}
