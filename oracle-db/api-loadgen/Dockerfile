FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY api_load_gen.py .

RUN chmod +x api_load_gen.py

CMD ["python", "-u", "api_load_gen.py"]
