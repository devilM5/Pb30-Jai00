package com.example.data.curriculum

import com.example.data.model.ChecklistItem
import com.example.data.model.KaliToolDoc
import com.example.data.model.LabModule
import com.example.data.model.LabQuizQuestion
import com.example.data.model.LabTrack
import com.example.data.model.NoteCategory
import com.example.data.model.SampleLabReport
import com.example.data.model.SecurityChecklist

object CyberCurriculumData {

    val labTracks: List<LabTrack> = listOf(
        LabTrack(
            id = "networking",
            title = "Networking Fundamentals",
            subtitle = "OSI layers, TCP/UDP ports, TLS handshakes & packet defense",
            category = NoteCategory.NETWORKING,
            difficulty = "Foundation",
            modules = listOf(
                LabModule(
                    id = "net_osi_tcp",
                    title = "OSI vs TCP/IP & Critical Ports",
                    summary = "Every network defense skill begins with understanding how data is encapsulated from Layer 7 (Application) down to Layer 1 (Physical) and how TCP 3-way handshakes (SYN, SYN-ACK, ACK) establish reliable sessions.",
                    keyConcepts = listOf(
                        "Layer 3 (Network): IP routing, CIDR subnet masks, ICMP diagnostics",
                        "Layer 4 (Transport): TCP stateful connections vs UDP connectionless datagrams",
                        "Common Service Ports: 22 (SSH), 53 (DNS), 80 (HTTP), 443 (HTTPS), 3306 (MySQL), 5432 (PostgreSQL)",
                        "Firewall Rule Principle: Default-deny inbound, allowlist only required ports & source CIDRs"
                    ),
                    commandOrCodeSnippet = """# Inspect listening sockets safely on your own Linux host
ss -tulnp

# Expected defensive check:
# Only 22/tcp (SSH) and 443/tcp (HTTPS) exposed externally;
# DB ports (5432/3306) bound strictly to 127.0.0.1""",
                    defensiveTakeaway = "Never expose administrative or database ports (22, 3306, 5432, 6379) directly to 0.0.0.0/0. Bind internal services to localhost or private VPC subnets.",
                    quiz = LabQuizQuestion(
                        question = "Which TCP flag sequence completes a standard 3-way handshake?",
                        options = listOf(
                            "SYN -> SYN-ACK -> ACK",
                            "ACK -> FIN -> RST",
                            "SYN -> PSH -> URG",
                            "GET -> 200 OK -> FIN"
                        ),
                        correctIndex = 0,
                        explanation = "The client sends SYN, the server responds with SYN-ACK, and the client confirms with ACK before data transfer begins."
                    )
                ),
                LabModule(
                    id = "net_tls_dns",
                    title = "DNS Security & TLS 1.3 Encryption",
                    summary = "DNS translates human domains into IP addresses, while TLS 1.3 protects traffic confidentiality and integrity against eavesdropping or on-path tampering.",
                    keyConcepts = listOf(
                        "TLS 1.3 eliminates legacy weak ciphers (RC4, SHA-1, RSA key transport) and enforces Perfect Forward Secrecy (ECDHE).",
                        "Certificate validation verifies the CA trust chain, hostname SAN match, and expiration window.",
                        "DNSSEC signs DNS records cryptographically to prevent cache poisoning.",
                        "HSTS (Strict-Transport-Security) instructs browsers never to downgrade HTTPS to HTTP."
                    ),
                    commandOrCodeSnippet = """# Verify TLS certificate & cipher negotiation on an authorized domain
openssl s_client -connect example.com:443 -tls1_3

# Enforce HSTS header in Nginx:
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;""",
                    defensiveTakeaway = "Disable TLS 1.0/1.1, enforce TLS 1.2+ or 1.3, automate certificate renewal, and enable HSTS.",
                    quiz = LabQuizQuestion(
                        question = "What does Perfect Forward Secrecy (PFS) guarantee in TLS 1.3?",
                        options = listOf(
                            "Past session traffic cannot be decrypted even if the server's long-term private key is later compromised",
                            "DNS queries are automatically routed over Tor",
                            "Servers never need X.509 certificates",
                            "Firewalls bypass port 443 inspection"
                        ),
                        correctIndex = 0,
                        explanation = "Ephemeral Diffie-Hellman (ECDHE) generates unique session keys per connection so long-term key compromise does not decrypt past sessions."
                    )
                )
            )
        ),
        LabTrack(
            id = "linux",
            title = "Linux Commands & Hardening",
            subtitle = "Permissions, process auditing, SSH lockdown & log forensics",
            category = NoteCategory.LINUX,
            difficulty = "Foundation",
            modules = listOf(
                LabModule(
                    id = "linux_perms",
                    title = "File Permissions, Ownership & SUID Risks",
                    summary = "Linux security relies on least-privilege discretionary access control (rwx for user, group, others) and careful auditing of SUID/SGID binaries.",
                    keyConcepts = listOf(
                        "r=4, w=2, x=1: chmod 600 restricts a private key to owner read/write only.",
                        "Never leave world-writable (777) scripts or config files in production.",
                        "SUID binaries execute with the file owner's privileges (often root); audit them regularly.",
                        "Use sudoers with explicit command allowlists rather than NOPASSWD: ALL."
                    ),
                    commandOrCodeSnippet = """# Secure an SSH private key and verify ownership
chmod 600 ~/.ssh/id_ed25519
chown ${'$'}USER:${'$'}USER ~/.ssh/id_ed25519

# Audit SUID binaries on your local system (defensive check)
find /usr/bin -perm -4000 -ls 2>/dev/null""",
                    defensiveTakeaway = "Apply chmod 600 on credentials/keys and regularly audit SUID binaries and sudoers rules.",
                    quiz = LabQuizQuestion(
                        question = "What permission mode should an SSH private key (~/.ssh/id_ed25519) have?",
                        options = listOf("600 (owner read/write only)", "777 (all read/write/execute)", "644 (world readable)", "755 (world executable)"),
                        correctIndex = 0,
                        explanation = "OpenSSH rejects private keys accessible by group or others; 600 restricts access strictly to the owner."
                    )
                ),
                LabModule(
                    id = "linux_logs",
                    title = "Log Auditing & SSH Hardening",
                    summary = "Defenders spot brute-force attempts, anomalous logins, and service crashes early by inspecting systemd journals and hardening SSH configuration.",
                    keyConcepts = listOf(
                        "Disable PasswordAuthentication and PermitRootLogin in /etc/ssh/sshd_config.",
                        "Prefer Ed25519 SSH keys with passphrases and Fail2ban rate limiting.",
                        "Review /var/log/auth.log or journalctl -u ssh for failed authentication spikes.",
                        "Keep unattended security upgrades active for kernel and OpenSSL patches."
                    ),
                    commandOrCodeSnippet = """# Inspect recent SSH authentication events
journalctl -u ssh --since "24 hours ago" --no-pager

# Hardened /etc/ssh/sshd_config snippet:
PermitRootLogin no
PasswordAuthentication no
PubkeyAuthentication yes""",
                    defensiveTakeaway = "Key-based SSH + disabled root login + log monitoring shuts down automated credential stuffing.",
                    quiz = LabQuizQuestion(
                        question = "Which sshd_config setting prevents attackers from brute-forcing user passwords over SSH?",
                        options = listOf(
                            "PasswordAuthentication no",
                            "AllowTcpForwarding yes",
                            "PermitEmptyPasswords yes",
                            "Banner /etc/issue"
                        ),
                        correctIndex = 0,
                        explanation = "Disabling PasswordAuthentication requires cryptographic SSH key pairs and defeats password brute-force attacks."
                    )
                )
            )
        ),
        LabTrack(
            id = "owasp",
            title = "OWASP Top 10",
            subtitle = "Broken Access Control, Injection, Crypto & SSRF defense",
            category = NoteCategory.OWASP,
            difficulty = "Intermediate",
            modules = listOf(
                LabModule(
                    id = "owasp_a01_a03",
                    title = "A01 Broken Access Control & A03 Injection",
                    summary = "Broken Access Control happens when users can act outside their intended permissions (e.g., IDOR). Injection occurs when untrusted input alters SQL, OS, or template commands.",
                    keyConcepts = listOf(
                        "A01 Defense: Deny by default, verify object ownership server-side (resource.userId == auth.uid).",
                        "Never trust client-supplied role flags, hidden form fields, or JWT claims without signature & server checks.",
                        "A03 Defense: Use parameterized queries / prepared statements—never concatenate user input into SQL.",
                        "Avoid Runtime.exec() or shell interpolation with unsanitized strings."
                    ),
                    commandOrCodeSnippet = """// VULNERABLE (Never do this):
val query = "SELECT * FROM users WHERE email = '" + userInput + "'"

// SECURE (Parameterized PreparedStatement):
val stmt = connection.prepareStatement("SELECT * FROM users WHERE email = ?")
stmt.setString(1, userInput)""",
                    defensiveTakeaway = "Always separate code from data using parameterized queries and enforce authorization checks on every server endpoint.",
                    quiz = LabQuizQuestion(
                        question = "What is the most reliable defense against SQL Injection?",
                        options = listOf(
                            "Parameterized queries / prepared statements",
                            "Hiding SQL errors in CSS",
                            "Base64-encoding the input on the client",
                            "Using HTTP POST instead of GET"
                        ),
                        correctIndex = 0,
                        explanation = "Prepared statements bind user input strictly as data literals, preventing input from changing query syntax."
                    )
                ),
                LabModule(
                    id = "owasp_a02_a10",
                    title = "A02 Cryptographic Failures & A10 SSRF",
                    summary = "Weak hashing, hardcoded secrets, and unencrypted transport leak sensitive data. SSRF tricks a server into making internal requests to cloud metadata or private subnets.",
                    keyConcepts = listOf(
                        "Hash passwords with Argon2id or bcrypt—never MD5, SHA-1, or unsalted SHA-256.",
                        "Use AES-256-GCM or ChaCha20-Poly1305 for authenticated encryption at rest.",
                        "SSRF Defense: Allowlist permitted outbound domains/IPs and block link-local metadata IPs (169.254.169.254).",
                        "Never hardcode API keys or signing secrets inside APK source files."
                    ),
                    commandOrCodeSnippet = """// SSRF Safe URL Check (Kotlin conceptual guard):
val host = URI(userUrl).host
val resolved = InetAddress.getByName(host)
require(!resolved.isLoopbackAddress && !resolved.isSiteLocalAddress && !resolved.isLinkLocalAddress) {
    "Blocked internal or metadata IP target"
}""",
                    defensiveTakeaway = "Use modern AEAD ciphers + Argon2id passwords, keep secrets out of client binaries, and validate server-side URLs against private IP ranges.",
                    quiz = LabQuizQuestion(
                        question = "Why should a server block outbound requests to 169.254.169.254?",
                        options = listOf(
                            "It protects cloud instance metadata credentials from Server-Side Request Forgery (SSRF)",
                            "It speeds up DNS resolution",
                            "It enables HTTP/3 compression",
                            "It prevents CSS layout shift"
                        ),
                        correctIndex = 0,
                        explanation = "Cloud providers expose IAM credentials and instance metadata at 169.254.169.254; SSRF can steal them if unblocked."
                    )
                )
            )
        ),
        LabTrack(
            id = "web",
            title = "Web Security Basics",
            subtitle = "XSS, CSRF, CORS, CSP & secure cookie attributes",
            category = NoteCategory.WEB_API,
            difficulty = "Intermediate",
            modules = listOf(
                LabModule(
                    id = "web_xss_csp",
                    title = "XSS Prevention & Content Security Policy",
                    summary = "Cross-Site Scripting (XSS) executes untrusted scripts in a victim's browser. Modern frameworks auto-escape output, reinforced by strict Content-Security-Policy headers.",
                    keyConcepts = listOf(
                        "Context-aware output encoding prevents HTML/JS/attribute injection.",
                        "Never pass untrusted strings into innerHTML or dangerouslySetInnerHTML without DOMPurify.",
                        "CSP restricts where scripts can load from (script-src 'self') and blocks inline script execution.",
                        "Set session cookies with HttpOnly, Secure, and SameSite=Lax or Strict."
                    ),
                    commandOrCodeSnippet = """# Strong HTTP Security Headers
Content-Security-Policy: default-src 'self'; script-src 'self'; object-src 'none'
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Set-Cookie: session=abc123; HttpOnly; Secure; SameSite=Strict""",
                    defensiveTakeaway = "Combine auto-escaping templates, strict CSP headers, and HttpOnly+Secure+SameSite cookies to stop XSS & session hijacking.",
                    quiz = LabQuizQuestion(
                        question = "Which cookie flag prevents JavaScript (document.cookie) from reading a session cookie?",
                        options = listOf("HttpOnly", "SameOrigin", "Cache-Control", "Access-Control-Allow-Origin"),
                        correctIndex = 0,
                        explanation = "HttpOnly prevents client-side scripts from accessing the cookie via document.cookie, reducing XSS session theft impact."
                    )
                ),
                LabModule(
                    id = "web_cors_csrf",
                    title = "CORS Misconfigurations & CSRF Defense",
                    summary = "CORS controls which cross-origin websites may read responses; CSRF tricks an authenticated browser into submitting unintended state-changing requests.",
                    keyConcepts = listOf(
                        "Never combine Access-Control-Allow-Origin: * (or reflected Origin) with Access-Control-Allow-Credentials: true.",
                        "Use explicit origin allowlists on the server.",
                        "Defend against CSRF with SameSite cookies + anti-CSRF synchronizer tokens on state-changing routes.",
                        "Verify Origin / Referer headers for sensitive mutations."
                    ),
                    commandOrCodeSnippet = """# Safe CORS Origin Allowlist (Pseudocode)
val allowedOrigins = setOf("https://app.example.com")
if (requestOrigin in allowedOrigins) {
    response.setHeader("Access-Control-Allow-Origin", requestOrigin)
    response.setHeader("Vary", "Origin")
}""",
                    defensiveTakeaway = "Never reflect arbitrary Origin headers with credentials enabled, and enforce SameSite + CSRF tokens.",
                    quiz = LabQuizQuestion(
                        question = "Why is reflecting any request Origin with Access-Control-Allow-Credentials: true dangerous?",
                        options = listOf(
                            "Any malicious site can make credentialed requests and read authenticated user data",
                            "It disables TLS encryption",
                            "It corrupts database indexes",
                            "It blocks mobile browsers from loading CSS"
                        ),
                        correctIndex = 0,
                        explanation = "Arbitrary origin reflection + Allow-Credentials lets third-party sites read private authenticated API responses."
                    )
                )
            )
        ),
        LabTrack(
            id = "api",
            title = "API Security",
            subtitle = "OWASP API Top 10, BOLA/IDOR, JWT safety & rate limiting",
            category = NoteCategory.WEB_API,
            difficulty = "Intermediate",
            modules = listOf(
                LabModule(
                    id = "api_bola_jwt",
                    title = "BOLA (IDOR) & JWT Verification Rules",
                    summary = "Broken Object Level Authorization (API1) is the #1 API flaw: changing /orders/104 to /orders/105 without checking ownership. JWTs also fail when algorithms or expiry aren't verified.",
                    keyConcepts = listOf(
                        "Verify resource ownership against the authenticated subject (sub / uid) on EVERY GET/PUT/DELETE.",
                        "Use non-guessable UUIDv4 or Firestore auto-IDs plus strict server ownership rules.",
                        "Always pin allowed JWT algorithms (e.g., RS256/ES256)—never accept alg: none.",
                        "Validate iss, aud, exp, and signature on every API call."
                    ),
                    commandOrCodeSnippet = """// Server-side BOLA guard example
fun getInvoice(invoiceId: String, currentUid: String): Invoice {
    val invoice = repo.findById(invoiceId) ?: throw NotFoundException()
    if (invoice.ownerUid != currentUid) throw ForbiddenException("Access denied")
    return invoice
}""",
                    defensiveTakeaway = "Authentication proves WHO you are; Authorization proves WHAT you can access. Check both on every object ID.",
                    quiz = LabQuizQuestion(
                        question = "What is Broken Object Level Authorization (BOLA / IDOR)?",
                        options = listOf(
                            "An API fails to verify that the authenticated user owns the requested object ID",
                            "An API returns XML instead of JSON",
                            "An API uses HTTPS on port 443",
                            "An API caches public images"
                        ),
                        correctIndex = 0,
                        explanation = "BOLA occurs when endpoints accept a record ID and return or modify data without verifying caller ownership."
                    )
                ),
                LabModule(
                    id = "api_rate_limit",
                    title = "Rate Limiting & Schema Validation",
                    summary = "Unrestricted resource consumption allows credential stuffing, scraping, and Denial of Wallet attacks. Strict schema validation prevents mass assignment.",
                    keyConcepts = listOf(
                        "Enforce per-user and per-IP token-bucket rate limits on auth, search, and AI endpoints.",
                        "Allowlist writable DTO fields—never bind raw JSON directly into database entities (Mass Assignment).",
                        "Cap pagination limits (e.g., max 50 items) and request payload sizes.",
                        "Avoid leaking stack traces or internal DB errors in 500 responses."
                    ),
                    commandOrCodeSnippet = """// Mass Assignment Defense: Explicit DTO mapping
data class UpdateProfileRequest(val displayName: String) // NO 'isAdmin' or 'role' field!
user.displayName = request.displayName.trim().take(40)""",
                    defensiveTakeaway = "Bind only allowlisted DTO fields, enforce strict rate limits, and keep error responses generic.",
                    quiz = LabQuizQuestion(
                        question = "How do you prevent Mass Assignment vulnerabilities in REST APIs?",
                        options = listOf(
                            "Bind input into explicit DTOs containing only user-editable fields",
                            "Disable JSON parsing",
                            "Return 200 OK for all errors",
                            "Increase server RAM"
                        ),
                        correctIndex = 0,
                        explanation = "Explicit DTO allowlisting ensures clients cannot overwrite sensitive internal fields like role or balance."
                    )
                )
            )
        ),
        LabTrack(
            id = "vuln",
            title = "Vulnerability Education",
            subtitle = "CVE anatomy, CVSS v3/v4 scoring & responsible disclosure",
            category = NoteCategory.GENERAL,
            difficulty = "Advanced",
            modules = listOf(
                LabModule(
                    id = "vuln_cve_cvss",
                    title = "CVE Lifecycle, CVSS & Patch Prioritization",
                    summary = "Security engineers triage vulnerabilities systematically using CVE identifiers, CWE root-cause classifications, CVSS severity scores, and exploitability context (EPSS/KEV).",
                    keyConcepts = listOf(
                        "CVE (Common Vulnerabilities and Exposures): Unique public ID like CVE-2024-XXXX.",
                        "CWE (Common Weakness Enumeration): Root flaw class (e.g., CWE-89 SQLi, CWE-79 XSS).",
                        "CVSS Base Score (0.0–10.0): Measures Attack Vector, Complexity, Privileges Required, and CIA impact.",
                        "Prioritize patching Internet-facing Critical/High CVEs and CISA KEV items first."
                    ),
                    commandOrCodeSnippet = """# Dependency vulnerability audit commands (Defensive DevSecOps)
./gradlew dependencyCheckAnalyze   # OWASP Dependency-Check
npm audit --production             # Node dependency audit
pip-audit                          # Python CVE audit""",
                    defensiveTakeaway = "Maintain a Software Bill of Materials (SBOM), automate dependency CVE scanning in CI, and follow coordinated vulnerability disclosure.",
                    quiz = LabQuizQuestion(
                        question = "What is the difference between a CVE and a CWE?",
                        options = listOf(
                            "CVE identifies a specific public vulnerability instance; CWE classifies the underlying weakness type",
                            "CVE is only for Linux; CWE is only for Windows",
                            "CWE is a firewall tool; CVE is an encryption algorithm",
                            "They are identical terms"
                        ),
                        correctIndex = 0,
                        explanation = "CWE describes the software flaw pattern (e.g. CWE-89 SQL Injection), while a CVE tracks a specific disclosed vulnerability."
                    )
                )
            )
        )
    )

    val ethicalHackingPhases: List<Pair<String, String>> = listOf(
        "1. Authorization & Rules of Engagement" to
            "Written signed scope (IP ranges, domains, time window, allowed tests) is mandatory BEFORE any assessment. Testing without explicit permission is illegal and unethical.",
        "2. Passive & Active Reconnaissance (In-Scope)" to
            "Review public DNS, TLS certificates, tech stack headers, and architecture diagrams to understand the attack surface without disrupting services.",
        "3. Vulnerability Enumeration & Verification" to
            "Identify misconfigurations, outdated dependencies, and weak access controls safely—confirming risk without causing downtime or data theft.",
        "4. Risk Rating & Impact Analysis" to
            "Classify findings using CVSS + business context (Confidentiality, Integrity, Availability) and separate false positives from verified weaknesses.",
        "5. Remediation Guidance & Retesting" to
            "Deliver actionable code/config fixes, verify patches in staging, and harden monitoring alerts to detect future regressions."
    )

    val kaliTools: List<KaliToolDoc> = listOf(
        KaliToolDoc(
            name = "Nmap",
            category = "Network Discovery (Authorized)",
            purpose = "Discovers open ports, running services, and TLS/banner versions on networks you own or have written permission to audit.",
            safeSyntax = "nmap -sV -sC --top-ports 100 -oN lab_scan.txt 192.168.56.10",
            simulatedLabOutput = """[SIMULATED LAB OUTPUT — NO LIVE SCAN PERFORMED]
Host: 192.168.56.10 (Local VirtualBox Lab VM)
PORT    STATE SERVICE  VERSION
22/tcp  open  ssh      OpenSSH 9.6p1
80/tcp  open  http     nginx 1.24.0 (Redirect -> 443)
443/tcp open  https    nginx 1.24.0 (TLS 1.3)
3306/tcp filtered mysql""",
            authorizationRule = "Run only against localhost, your own isolated lab VMs, or explicitly authorized IP ranges."
        ),
        KaliToolDoc(
            name = "Wireshark / tshark",
            category = "Packet Inspection",
            purpose = "Captures and inspects network frames to troubleshoot TLS handshakes, DNS anomalies, and plaintext protocol leaks.",
            safeSyntax = "tshark -i eth0 -f \"tcp port 443\" -c 25",
            simulatedLabOutput = """[SIMULATED LAB OUTPUT — NO LIVE CAPTURE]
1  0.000  10.0.2.15 -> 10.0.2.2  TLSv1.3 Client Hello
2  0.012  10.0.2.2  -> 10.0.2.15 TLSv1.3 Server Hello, Change Cipher Spec
3  0.014  10.0.2.15 -> 10.0.2.2  TLSv1.3 Application Data (Encrypted)""",
            authorizationRule = "Inspect only traffic on interfaces and segments where monitoring is authorized."
        ),
        KaliToolDoc(
            name = "Burp Suite / OWASP ZAP",
            category = "Web & API Proxy",
            purpose = "Intercepts your own browser/app HTTP requests to inspect headers, cookies, CSRF tokens, and authorization enforcement.",
            safeSyntax = "zap-baseline.py -t https://staging.local-lab.test -r report.html",
            simulatedLabOutput = """[SIMULATED LAB OUTPUT — NO LIVE SCAN]
PASS: X-Content-Type-Options header present
WARN-NEW: Content-Security-Policy header missing on /login
WARN-NEW: Cookie missing SameSite attribute""",
            authorizationRule = "Use passive/baseline checks against your own staging environment with test accounts."
        ),
        KaliToolDoc(
            name = "Nikto",
            category = "Web Server Misconfig Audit",
            purpose = "Checks authorized web servers for exposed backup files, server version leaks, and missing security headers.",
            safeSyntax = "nikto -h https://192.168.56.10 -Tuning 123",
            simulatedLabOutput = """[SIMULATED LAB OUTPUT — NO LIVE SCAN]
+ Target IP: 192.168.56.10
+ Server: nginx/1.24.0
+ The anti-clickjacking X-Frame-Options header is not present.
+ OSVDB-3268: /backup/ directory indexing found.""",
            authorizationRule = "Generates noisy HTTP traffic; run strictly in approved maintenance windows on owned/lab servers."
        ),
        KaliToolDoc(
            name = "Lynis",
            category = "Defensive Host Auditing",
            purpose = "Local open-source security auditing tool for Linux/macOS that scores kernel, SSH, firewall, and file permission hardening.",
            safeSyntax = "sudo lynis audit system --quick",
            simulatedLabOutput = """[SIMULATED LAB OUTPUT — NO LIVE AUDIT]
[+] SSH Daemon: PermitRootLogin = NO [OK]
[!] Firewall: UFW inactive [SUGGESTION: Enable ufw default deny incoming]
Hardening index : 74 [##############      ]""",
            authorizationRule = "Runs locally on systems you administer to generate a defensive hardening score."
        ),
        KaliToolDoc(
            name = "Sqlmap (Concept)",
            category = "Authorized Injection Testing",
            purpose = "Demonstrates why parameterized queries are critical by testing authorized staging forms for SQL injection.",
            safeSyntax = "# Always test your own staging lab only with written authorization",
            simulatedLabOutput = """[SIMULATED LAB OUTPUT — EDUCATIONAL CONCEPT]
[INFO] Parameter 'search' appears safely parameterized (no injectable error/time variance).
Recommendation: Keep PreparedStatement binding intact.""",
            authorizationRule = "Never run automated injection tools against any third-party website or production system without a written pentest contract."
        )
    )

    val checklists: List<SecurityChecklist> = listOf(
        SecurityChecklist(
            id = "web_hardening",
            title = "Web App Defense Checklist",
            domain = "OWASP Web",
            items = listOf(
                ChecklistItem("w1", "Enforce HTTPS & HSTS", "Redirect all port 80 traffic to 443 and set Strict-Transport-Security."),
                ChecklistItem("w2", "Configure Strict CSP", "Set Content-Security-Policy to block inline scripts and unknown script origins."),
                ChecklistItem("w3", "Harden Session Cookies", "Apply HttpOnly, Secure, and SameSite=Lax/Strict on all auth cookies."),
                ChecklistItem("w4", "Parameterized DB Queries", "Verify zero string-concatenated SQL/NoSQL queries remain."),
                ChecklistItem("w5", "Server-Side Access Control", "Confirm every endpoint verifies user ownership before returning records.")
            )
        ),
        SecurityChecklist(
            id = "api_hardening",
            title = "REST / GraphQL API Checklist",
            domain = "OWASP API",
            items = listOf(
                ChecklistItem("a1", "Verify Object Ownership (BOLA)", "Check ownerUid == auth.uid on every GET/PATCH/DELETE by ID."),
                ChecklistItem("a2", "Strict JWT Validation", "Validate signature, exp, iss, aud, and reject alg=none."),
                ChecklistItem("a3", "Rate Limiting Active", "Apply per-IP and per-user throttling on login, OTP, and AI endpoints."),
                ChecklistItem("a4", "DTO Allowlisting", "Prevent mass assignment by binding only safe explicit input fields."),
                ChecklistItem("a5", "Sanitized Error Payloads", "Return clean error codes without internal stack traces.")
            )
        ),
        SecurityChecklist(
            id = "linux_hardening",
            title = "Linux Server Hardening",
            domain = "Infrastructure",
            items = listOf(
                ChecklistItem("l1", "Disable SSH Root & Passwords", "Set PermitRootLogin no and PasswordAuthentication no in sshd_config."),
                ChecklistItem("l2", "Enable Host Firewall (UFW/nftables)", "Default-deny inbound; allow only 22 (restricted) and 443."),
                ChecklistItem("l3", "Unattended Security Updates", "Enable automatic patching for OS and OpenSSL packages."),
                ChecklistItem("l4", "Least-Privilege File Permissions", "Verify 600 on keys/.env and audit world-writable files."),
                ChecklistItem("l5", "Centralized Log Alerting", "Monitor auth failures and sudo usage via journald/auditd.")
            )
        ),
        SecurityChecklist(
            id = "android_hardening",
            title = "Android App Security Checklist",
            domain = "Mobile / MASVS",
            items = listOf(
                ChecklistItem("m1", "Zero Hardcoded API Keys", "Use Firebase App Check / backend proxies instead of embedded keys."),
                ChecklistItem("m2", "Minimal Permissions", "Use Android Photo Picker and request runtime permissions only when needed."),
                ChecklistItem("m3", "Exported Components Audit", "Set android:exported=false on internal services, receivers, and providers."),
                ChecklistItem("m4", "HTTPS Only (Cleartext Disabled)", "Keep usesCleartextTraffic=false and enforce TLS 1.2+."),
                ChecklistItem("m5", "Zero-Trust Cloud Rules", "Verify Firestore/backend rules enforce auth UID isolation and schema checks.")
            )
        )
    )

    val sampleReports: List<SampleLabReport> = listOf(
        SampleLabReport(
            id = "sample_nmap_http",
            title = "Sample Lab Scan #1 (Simulated Nmap + Headers)",
            label = "SIMULATED LAB SAMPLE",
            rawContent = """[SIMULATED LAB REPORT - STAGING VM 192.168.56.20]
22/tcp   open  ssh      OpenSSH 8.2p1 (PasswordAuthentication yes)
80/tcp   open  http     Apache httpd 2.4.41 (No HTTPS redirect)
3306/tcp open  mysql    MySQL 8.0.32 (Bound to 0.0.0.0)
HTTP Response Headers on /login:
Server: Apache/2.4.41 (Ubuntu)
Set-Cookie: PHPSESSID=xyz123; path=/
Missing: Content-Security-Policy, Strict-Transport-Security, X-Frame-Options"""
        ),
        SampleLabReport(
            id = "sample_api_audit",
            title = "Sample Lab Scan #2 (Simulated API Audit)",
            label = "SIMULATED LAB SAMPLE",
            rawContent = """[SIMULATED API STAGING AUDIT LOG]
GET /api/v1/invoices/1042 -> 200 OK (Returned another user's invoice when requested with User B's JWT)
POST /api/v1/login -> 200 OK (Tested 120 requests/min without rate limiting or 429 response)
Response CORS Header: Access-Control-Allow-Origin: https://evil-example.test, Access-Control-Allow-Credentials: true"""
        )
    )
}
