"""
Shared-secret check for service-to-service calls. Java sends the same
X-Internal-Secret header it expects from us on the callback. Fails closed:
if INTERNAL_SERVICE_SECRET is unset, every request is rejected.
"""

import hmac

from fastapi import Header, HTTPException

from app.config import settings


def require_internal_secret(x_internal_secret: str | None = Header(default=None)) -> None:
    expected = settings.internal_service_secret
    if not expected or not x_internal_secret or not hmac.compare_digest(
        expected.encode(), x_internal_secret.encode()
    ):
        raise HTTPException(status_code=401, detail="Invalid or missing internal service secret")
