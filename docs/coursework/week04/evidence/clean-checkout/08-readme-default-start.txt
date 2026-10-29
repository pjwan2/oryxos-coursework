# java -jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar   (no arguments, clean checkout, working dir = repo root)
--- GET /api/v1/health
{"code":0,"message":"ok","data":{"status":"ok","checks":{"database":"ok"}},"timestamp":"2026-10-29T12:04:36.810915Z"}
--- GET /api/v1/profiles (names)
['memory-demo', 'payment-demo']
--- POST invoke memory-demo save_memory
{"code":0,"message":"ok","data":{"agent":"memory-demo","sessionId":"api:default:memory-demo","reply":"Tool result: Saved to ARCHIVAL memory"},"timestamp":"2026-10-29T12:04:37.063494500Z"}
--- .oryxos/memory/MEMORY.md
## 核心记忆

## 归档记忆
- [2026-10-29] met Sam at the harbour
--- GET /admin/ (status, first bytes)
200 text/html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <meta name="robots" content="noindex" />
 
--- GET /admin/scheduler (status)
200
--- GET /swagger-ui/index.html
200
--- GET /actuator/prometheus (first matching line)
# HELP jvm_memory_used_bytes The amount of used memory
--- netstat: listening address of the server
  TCP    127.0.0.1:8080         0.0.0.0:0              LISTENING       23920
