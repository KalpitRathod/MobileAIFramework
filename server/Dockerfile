FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PORT=8080

WORKDIR /app

# Install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy gateway source code
COPY . .

# Cloud Run listens on PORT environment variable
EXPOSE 8080

CMD exec uvicorn main:app --host 0.0.0.0 --port ${PORT}
