FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd -u 10001 app
COPY server.py .
COPY static static
RUN mkdir /data && chown app:app /data
USER app
ENV DATABASE=/data/bydmate.db PYTHONDONTWRITEBYTECODE=1
EXPOSE 8080
CMD ["gunicorn", "--bind", "0.0.0.0:8080", "--workers", "2", "--threads", "4", "--timeout", "30", "--no-control-socket", "server:app"]
