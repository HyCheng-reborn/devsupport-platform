# 故障场景：SSL 证书过期

## 故障现象

生产环境 HTTPS 请求大面积失败，浏览器访问时显示"您的连接不是私密连接"（NET::ERR_CERT_DATE_INVALID）。后端服务间调用也出现异常：

```
ERROR o.a.h.impl.execchain.MainClientExec - SSL handshake failed
javax.net.ssl.SSLHandshakeException: PKIX path validation failed: java.security.cert.CertPathValidatorException: validity check failed
    at java.base/sun.security.ssl.Alert.createSSLException(Alert.java:131)
Caused by: java.security.cert.CertificateExpiredException: NotAfter: Wed Oct 02 12:00:00 UTC 2024
    at java.base/sun.security.x509.CertificateValidity.valid(CertificateValidity.java:277)
ERROR o.s.w.r.function.server.DefaultRoutingFunction - Request handling failed
reactor.netty.http.client.PrematureCloseException: Connection prematurely closed BEFORE response
```

证书过期时间为 2024-10-02 12:00 UTC，故障开始于 2024-10-02 12:01 UTC。

## 环境信息

- Service: api-gateway
- Environment: production
- Version: 2.0.5

## 根因分析

Let's Encrypt 签发的 TLS 证书有效期为 90 天，自动续期依赖 certbot 的 systemd timer（`certbot-renew.timer`）。该 timer 在 2 周前的一次系统更新中被意外禁用（`systemctl disable certbot-renew.timer`），导致证书到期后未能自动续期。

运维团队在 3 个月前从 cron job 迁移到 systemd timer 管理 certbot，但迁移过程中未验证 timer 是否正常运行。证书到期前也未配置到期告警。

## 排查步骤

1. **检查证书有效期**：执行 `openssl s_client -connect api.example.com:443 -servername api.example.com 2>/dev/null | openssl x509 -noout -dates`，确认 `notAfter=Oct  2 12:00:00 2024 GMT`，已过期。

2. **查看 certbot 状态**：执行 `systemctl status certbot-renew.timer`，发现 timer 状态为 `inactive (dead)` 且 `enabled: no`。进一步执行 `journalctl -u certbot-renew.timer --since "2 weeks ago"` 发现 timer 在 14 天前被手动停止。

3. **检查续期日志**：查看 `/var/log/letsencrypt/letsencrypt.log`，最后一条续期尝试记录在 91 天前（上一次成功续期），之后再无续期记录。

4. **验证 DNS 配置**：确认域名 DNS 解析正确指向当前服务器 IP，排除 DNS 变更导致的续期失败。执行 `certbot certificates` 确认当前证书列表和到期时间。

## 解决方案

**即时修复**：

1. 手动续期证书：
   ```bash
   certbot renew --force-renewal
   systemctl reload nginx
   ```

2. 验证新证书生效：
   ```bash
   openssl s_client -connect api.example.com:443 -servername api.example.com 2>/dev/null \
     | openssl x509 -noout -dates
   ```

3. 重新启用并启动 systemd timer：
   ```bash
   systemctl enable --now certbot-renew.timer
   systemctl status certbot-renew.timer
   ```

**预防措施**：

- 配置证书到期监控（提前 30 天、14 天、7 天、1 天告警）：
  ```bash
  # /etc/prometheus/rules/ssl-certificate.yml
  - alert: SSLCertificateExpiringSoon
    expr: (ssl_certificate_not_after - time()) / 86400 < 30
    labels:
      severity: warning
  ```
- 添加 systemd timer 状态监控，timer 停止时触发告警
- 变更系统配置后（如系统更新）检查关键 timer/service 状态
- 建立证书续期 SOP，包含迁移后的验证步骤

## 相关配置

```nginx
# /etc/nginx/conf.d/api.example.com.conf
server {
    listen 443 ssl http2;
    server_name api.example.com;

    ssl_certificate /etc/letsencrypt/live/api.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.example.com/privkey.pem;
    ssl_trusted_certificate /etc/letsencrypt/live/api.example.com/chain.pem;

    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256;
    ssl_prefer_server_ciphers off;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;

    # OCSP Stapling
    ssl_stapling on;
    ssl_stapling_verify on;
}
```

```ini
# /etc/systemd/system/certbot-renew.timer
[Unit]
Description=Run certbot-renew.service twice daily

[Timer]
OnCalendar=*-*-* 00,12:00:00
RandomizedDelaySec=1h
Persistent=true

[Install]
WantedBy=timers.target
```
