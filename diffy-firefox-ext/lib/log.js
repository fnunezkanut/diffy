// Diagnostic logger for the Diffy extension (classic script, also loadable with require()).
//
// Writes `[diffy] <ISO time> <LEVEL> <scope>: <message> <details JSON>` to the console and keeps the
// last 200 entries in chrome.storage.local (key "diffyLog"), so history survives service worker restarts.
// Debug entries are dropped unless chrome.storage.local "diffyDebug" is true.
//
// Details are sanitized: keys that look sensitive (token, cookie, authorization, password, secret,
// session, title, body, text) are dropped, only primitives are kept and strings are cut to 200 chars.
(function (root) {
  const LOG_KEY = "diffyLog";
  const DEBUG_KEY = "diffyDebug";
  const MAX_ENTRIES = 200;
  const MAX_STRING = 200;
  const SENSITIVE_KEY = /token|cookie|authorization|password|secret|session|title|body|text/i;

  function sanitize(details) {
    const clean = {};
    if (!details || typeof details !== "object") {
      return clean;
    }
    for (const [key, value] of Object.entries(details)) {
      if (SENSITIVE_KEY.test(key)) {
        continue;
      }
      if (value === null || typeof value === "number" || typeof value === "boolean") {
        clean[key] = value;
      } else if (typeof value === "string") {
        clean[key] = value.length > MAX_STRING ? value.slice(0, MAX_STRING) : value;
      }
    }
    return clean;
  }

  function defaultStorage() {
    if (typeof chrome === "undefined" || !chrome.storage || !chrome.storage.local) {
      return null;
    }
    return {
      async get(key) {
        const stored = await chrome.storage.local.get(key);
        return stored[key];
      },
      async set(key, value) {
        await chrome.storage.local.set({ [key]: value });
      },
      async remove(key) {
        await chrome.storage.local.remove(key);
      },
    };
  }

  function createLogger({ consoleImpl = console, storage = defaultStorage(), now = () => Date.now() } = {}) {
    let debugEnabled = false;
    let chain = Promise.resolve();
    let storageWarned = false;

    function warnStorageOnce(error) {
      if (!storageWarned) {
        storageWarned = true;
        consoleImpl.warn("[diffy] could not persist the log", error && error.message);
      }
    }

    if (storage) {
      Promise.resolve(storage.get(DEBUG_KEY))
        .then((value) => {
          debugEnabled = value === true;
        })
        .catch(() => {});
      if (typeof chrome !== "undefined" && chrome.storage && chrome.storage.onChanged) {
        chrome.storage.onChanged.addListener((changes, area) => {
          if (area === "local" && changes[DEBUG_KEY]) {
            debugEnabled = changes[DEBUG_KEY].newValue === true;
          }
        });
      }
    }

    function persist(entry) {
      if (!storage) {
        return;
      }
      chain = chain
        .then(async () => {
          const entries = (await storage.get(LOG_KEY)) || [];
          entries.push(entry);
          await storage.set(LOG_KEY, entries.slice(-MAX_ENTRIES));
        })
        .catch(warnStorageOnce);
    }

    function write(level, scope, message, details) {
      if (level === "debug" && !debugEnabled) {
        return;
      }
      const time = now();
      const clean = sanitize(details);
      const hasDetails = Object.keys(clean).length > 0;
      const line = `[diffy] ${new Date(time).toISOString()} ${level.toUpperCase()} ${scope}: ${message}`;
      const method = typeof consoleImpl[level] === "function" ? level : "log";
      if (hasDetails) {
        consoleImpl[method](line, JSON.stringify(clean));
      } else {
        consoleImpl[method](line);
      }
      persist({ t: time, level, scope, message, details: clean });
    }

    return {
      debug: (scope, message, details) => write("debug", scope, message, details),
      info: (scope, message, details) => write("info", scope, message, details),
      warn: (scope, message, details) => write("warn", scope, message, details),
      error: (scope, message, details) => write("error", scope, message, details),
      async getEntries() {
        await chain;
        return storage ? (await storage.get(LOG_KEY)) || [] : [];
      },
      async clear() {
        await chain;
        if (storage) {
          await storage.remove(LOG_KEY);
        }
      },
    };
  }

  const api = { LOG_KEY, DEBUG_KEY, MAX_ENTRIES, sanitize, createLogger };

  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  } else {
    root.DiffyLogLib = api;
    root.DiffyLog = createLogger();
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
