"""Recent DNS names per account, to find which domains a game really needs.

Deliberately volatile: kept in this worker's memory, bounded per account and in total, never written to the
database. It is a diagnostic aid, not a browsing history. With several workers an admin sees the worker that
answered; reload to sample another.
"""

import time
from collections import OrderedDict, deque

PER_ACCOUNT = 200
MAX_ACCOUNTS = 1000
TTL = 3600  # entries older than an hour are not shown

_accounts: OrderedDict[int, deque] = OrderedDict()


def label(rule):
    """How the query was answered: through the gateway, blocked, not ready yet, or straight to the real server."""
    action = rule["action"] if rule else "allow"
    return {"rewrite": "via_gateway", "block": "blocked", "unavailable": "pending"}.get(action, "direct")


def record(account_id, name, qtype, outcome):
    rows = _accounts.get(account_id)
    if rows is None:
        rows = _accounts[account_id] = deque(maxlen=PER_ACCOUNT)
        while len(_accounts) > MAX_ACCOUNTS:
            _accounts.popitem(last=False)
    else:
        _accounts.move_to_end(account_id)
    rows.append((time.time(), name, qtype, outcome))


def recent(account_id, *, limit=PER_ACCOUNT):
    """Newest first, one row per name+outcome with a count."""
    cutoff = time.time() - TTL
    merged: dict[tuple, dict] = {}
    for at, name, qtype, outcome in reversed(_accounts.get(account_id, ())):
        if at < cutoff:
            break
        item = merged.setdefault((name, outcome), {"name": name, "outcome": outcome, "count": 0, "last": at})
        item["count"] += 1
    return list(merged.values())[:limit]


def clear(account_id=None):
    if account_id is None:
        _accounts.clear()
    else:
        _accounts.pop(account_id, None)
