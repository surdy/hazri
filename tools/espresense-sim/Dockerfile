FROM python:3.12-slim

WORKDIR /app

RUN pip install --no-cache-dir "paho-mqtt>=2.1.0" "PyYAML>=6.0"

COPY espresense_sim/ ./espresense_sim/
COPY sim.py ./

ENV PYTHONUNBUFFERED=1
ENTRYPOINT ["python", "sim.py"]
CMD ["--host=mosquitto"]
