import sqlite3
from pathlib import Path
conn=sqlite3.connect(':memory:')
conn.executescript('CREATE TABLE companion_state(key TEXT PRIMARY KEY,value_json TEXT NOT NULL,updated_at TEXT NOT NULL);')
import re
worker = (Path(__file__).resolve().parents[1] / 'server/cloudflare-worker/worker.js').read_text()
sql = re.search(r'const quota = await env.DB.prepare\(`(.*?)`\)', worker, re.S).group(1)
count=0
for i in range(31):
  before=conn.total_changes
  conn.execute(sql,('study_ai_quota:android-phone:2026-10-12','2026-10-12T09:20:00Z'))
  changed=conn.total_changes-before
  if i<30: assert changed==1
  else: assert changed==0
  count+=changed
assert count==30
assert conn.execute('SELECT value_json FROM companion_state').fetchone()[0]=='30'
print('PASS: SQLite/D1-compatible atomic upsert rejects request 31 of daily 30')
