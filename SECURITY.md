# Java4Shell — CVE-2026-114938

This Android application executes on ART rather than the vulnerable
OpenJDK HotSpot runtime, but Gradle, Android SDK command-line tools
and JVM unit tests use OpenJDK in GitHub Actions and on developer machines.

The GitHub Actions workflow sets `JAVA_TOOL_OPTIONS=-XX:-UseBASE64Intrinsics`
as an interim mitigation that disables the vulnerable Base64 intrinsic.

Use a **vendor-confirmed patched JDK/JRE** for local builds and self-hosted
CI. Until available, set the same environment variable and restart Java
processes/Gradle daemons. Verify with
`java -XX:+PrintFlagsFinal -version` and look for
`UseBASE64Intrinsics = false`. PowerShell:
`$env:JAVA_TOOL_OPTIONS = '-XX:-UseBASE64Intrinsics'`.

References: https://www.cert.europa.eu/publications/security-advisories/2026-021/
and https://adoptium.net/news/2026/11/temurin-november-2026-cspu-java4shell/.
