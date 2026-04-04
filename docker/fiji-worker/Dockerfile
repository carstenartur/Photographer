# Fiji/ImageJ2 headless worker container for batch image processing
# This container provides a headless Fiji installation for advanced
# image analysis operations (stacking, registration, etc.)
FROM eclipse-temurin:21-jre

# Install dependencies for headless Fiji
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget unzip fontconfig libfreetype6 libx11-6 libxext6 libxrender1 \
    && rm -rf /var/lib/apt/lists/*

# Download and install Fiji
ARG FIJI_VERSION=latest
RUN wget -q "https://downloads.imagej.net/fiji/${FIJI_VERSION}/fiji-linux64.zip" \
        -O /tmp/fiji.zip \
    && unzip -q /tmp/fiji.zip -d /opt \
    && rm /tmp/fiji.zip \
    && chmod +x /opt/Fiji.app/ImageJ-linux64

# Create non-root user
RUN groupadd -r fiji && useradd -r -g fiji -d /home/fiji -m fiji

# Set up working directory for processing
RUN mkdir -p /data/input /data/output && chown -R fiji:fiji /data

# Configure headless operation
ENV DISPLAY=:99
ENV JAVA_HOME=/opt/java/openjdk

WORKDIR /data
USER fiji

# Healthcheck verifies Fiji runs headless
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD /opt/Fiji.app/ImageJ-linux64 --headless --run "print('OK');" 2>/dev/null | grep -q OK

# Default: run Fiji headless with macro from stdin or command line
ENTRYPOINT ["/opt/Fiji.app/ImageJ-linux64", "--headless"]
CMD ["--help"]
