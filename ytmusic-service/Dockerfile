FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PORT=8080 \
    YTMUSIC_AUTH_FILE=/data/auth.json \
    COOKIES_FILE=/data/cookies.txt

WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
RUN mkdir -p /data

EXPOSE 8080
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT}"]
