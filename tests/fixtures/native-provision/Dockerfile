# Disposable SSH acceptance target. Production installs use the real systemctl.
FROM debian:bookworm-slim
USER root
RUN apt-get update && apt-get install -y --no-install-recommends openssh-server python3 libpcap0.8 ca-certificates openssl iproute2 iptables iputils-ping wireguard-tools nftables && rm -rf /var/lib/apt/lists/* && mkdir -p /run/sshd /root/.ssh && chmod 700 /root/.ssh && ssh-keygen -A && usermod -p x root
COPY systemctl /usr/local/bin/systemctl
RUN chmod 755 /usr/local/bin/systemctl
ENTRYPOINT ["/usr/sbin/sshd", "-D", "-e", "-o", "PasswordAuthentication=no", "-o", "PermitRootLogin=prohibit-password"]
