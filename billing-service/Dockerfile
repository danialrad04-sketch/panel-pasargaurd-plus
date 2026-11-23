FROM python:3.12-slim
WORKDIR /srv/billing
COPY billing-service/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && useradd --system --uid 10001 billing \
    && install -d -o billing -g billing /data
COPY --chown=billing:billing billing-service/main.py billing-service/customer_accounts.py billing-service/provisioning.py .
COPY --chown=billing:billing billing-service/web ./web
COPY --chown=billing:billing android-client/app/src/main/assets/plans_config.json .
ENV BILLING_DB=/data/billing.sqlite3 BILLING_PLANS=/srv/billing/plans_config.json
USER billing
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080", "--no-access-log"]
