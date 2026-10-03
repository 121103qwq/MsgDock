CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  username TEXT NOT NULL,
  email TEXT,
  password_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS users_username_lower_idx ON users(lower(username));
CREATE UNIQUE INDEX IF NOT EXISTS users_email_lower_idx ON users(lower(email)) WHERE email IS NOT NULL;

CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  name TEXT NOT NULL,
  type TEXT NOT NULL CHECK (type IN ('android', 'windows', 'web')),
  device_token_hash TEXT NOT NULL UNIQUE,
  last_seen_at INTEGER,
  created_at INTEGER NOT NULL,
  revoked_at INTEGER
);

CREATE INDEX IF NOT EXISTS devices_user_idx ON devices(user_id, revoked_at, created_at);

CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  revoked_at INTEGER
);

CREATE INDEX IF NOT EXISTS sessions_lookup_idx ON sessions(token_hash, expires_at, revoked_at);
CREATE TABLE IF NOT EXISTS auth_rate_limits (
  ip TEXT PRIMARY KEY,
  window_started_at INTEGER NOT NULL,
  login_count INTEGER NOT NULL DEFAULT 0,
  register_count INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS messages (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL,
  source_device_id TEXT NOT NULL,
  client_message_id TEXT NOT NULL,
  sender TEXT NOT NULL,
  body TEXT NOT NULL,
  received_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE(user_id, client_message_id)
);

CREATE INDEX IF NOT EXISTS messages_user_seq_idx ON messages(user_id, seq);
