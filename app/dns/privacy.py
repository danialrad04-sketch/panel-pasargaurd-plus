"""Redact DNS URL credentials and wire questions in Uvicorn's access logger."""

import logging
import re

DNS_PATH = re.compile(r"(/dns-query)(/[^?\s\"']+)?(\?[^\s\"']*)?")


def redact(value):
    return DNS_PATH.sub(
        lambda match: match[1] + ("/[redacted]" if match[2] else "") + ("?[redacted]" if match[3] else ""), value
    )


class DNSAccessFilter(logging.Filter):
    def filter(self, record):
        if isinstance(record.args, tuple):
            record.args = tuple(redact(value) if isinstance(value, str) else value for value in record.args)
        if isinstance(record.msg, str):
            record.msg = redact(record.msg)
        return True


def install_access_filter():
    logger = logging.getLogger("uvicorn.access")
    if not any(isinstance(item, DNSAccessFilter) for item in logger.filters):
        logger.addFilter(DNSAccessFilter())
