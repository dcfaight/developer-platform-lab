# Automation

Python 3.12 scripts and tests for environment expiration.
No third-party dependencies are needed for the scaffold.

`cleanup_expired.py` is a non-operational placeholder and exits with an error
if invoked. Future implementation will select expired environments through the
backend API, default to dry-run, and request deletion through the same API.
It must not delete Kubernetes namespaces directly.

Syntax check from the repository root:

```sh
python -m compileall -q automation
```

`tests/` is reserved for cleanup behavior tests once implementation begins.
