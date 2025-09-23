from __future__ import annotations
import json, logging, sys, time

# Custom logger that outputs JSON formatted logs
class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "timestamp": time.time(),
            "level": record.levelname,
            "msg": record.getMessage(),
        }

        # Include exception info if present
        if record.exc_info:
            payload["exec_info"] = self.formatException(record.exc_info)

        # Include any extra fields passed in the log call
        for k in getattr(record, "args", {}) if isinstance(record.args, dict) else {}:
            payload[k] = record.args[k]

        return json.dumps(payload)

# Setup logging config for the application
def SetupLogging(level: str = "INFO", jsonLogs: bool = True) -> None:
    root = logging.getLogger()
    root.handlers.clear()
    root.setLevel(getattr(logging, level.upper(), logging.INFO))

    # Make sure handler logs to standard output
    h = logging.StreamHandler(sys.stdout)

    # Set the formatter to JSON or plain text based on config
    h.setFormatter(JsonFormatter() if jsonLogs else logging.Formatter(
        "%(asctime)s - %(name)s - %(levelname)s - %(message)s"
    ))

    root.addHandler(h)