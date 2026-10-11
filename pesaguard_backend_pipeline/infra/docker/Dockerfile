FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

# Keep WORKDIR at /app so `python -m pesaguard_backend_pipeline.<module>` resolves:
# the package lives at /app/pesaguard_backend_pipeline and /app is on sys.path.
WORKDIR /app

# Install dependencies first for better layer caching.
COPY pesaguard_backend_pipeline/requirements_3.txt ./requirements_3.txt
RUN pip install --no-cache-dir -r requirements_3.txt

# Copy application source. Secrets (.env), git history, and caches are excluded
# by the repository-root .dockerignore so they are never baked into the image.
COPY . .

RUN mkdir -p /tmp/pesaguard-prometheus \
    && sed -i 's/\r$//' pesaguard_backend_pipeline/worker_entrypoint.sh

# Run as an unprivileged user (least privilege - PesaGuard Rule #56).
RUN useradd --create-home --shell /usr/sbin/nologin appuser \
    && chown -R appuser:appuser /app /tmp/pesaguard-prometheus
USER appuser

EXPOSE 5001

# Docker-native liveness probe (PesaGuard Rule #40). Uses /live, which reports
# process liveness only (no downstream dependency), so a transient Redis/DB blip
# never triggers an unnecessary container restart. urllib avoids adding curl to
# the slim image. A refused connection or non-200 makes the probe exit non-zero.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python -c "import os,urllib.request; p=os.getenv('PORT','5001'); assert urllib.request.urlopen('http://127.0.0.1:'+p+'/live',timeout=3).status==200"

CMD ["python", "-m", "pesaguard_backend_pipeline.canonical_app"]
