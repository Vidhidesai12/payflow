# syntax=docker/dockerfile:1
# analytics-mcp: read-only analytics MCP server.  docker build -t analytics-mcp analytics-mcp
FROM python:3.13-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /app
COPY pyproject.toml ./
COPY src ./src
RUN pip install . && useradd --system --no-create-home app
USER app
# Listen on all interfaces inside the container; Host-header checks (ALLOWED_HOSTS) still apply.
ENV HOST=0.0.0.0 PORT=8084
EXPOSE 8084
HEALTHCHECK --interval=10s --timeout=3s --retries=6 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8084/health', timeout=2)"
CMD ["python", "-m", "analytics_mcp"]
