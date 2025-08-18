# TLS API Documentation

## Overview

The Multi-Modal Sensor Platform implements enterprise-grade TLS security through a comprehensive API that provides secure communication between the PC Hub and Android Spoke devices. This documentation covers the complete TLS implementation, configuration, and usage patterns.

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Core Components](#core-components)
3. [Configuration Management](#configuration-management)
4. [API Reference](#api-reference)
5. [Usage Examples](#usage-examples)
6. [Security Features](#security-features)
7. [Certificate Management](#certificate-management)
8. [Error Handling](#error-handling)
9. [Performance Considerations](#performance-considerations)
10. [Troubleshooting](#troubleshooting)

## Architecture Overview

The TLS implementation follows a layered architecture designed for security, flexibility, and ease of use:

```
┌─────────────────────────────────────────┐
│           Application Layer             │
├─────────────────────────────────────────┤
│        SecureMessageHandler            │
├─────────────────────────────────────────┤
│       SecureConnectionManager          │
├─────────────────────────────────────────┤
│           TLSConfig                     │
├─────────────────────────────────────────┤
│        Python ssl module               │
└─────────────────────────────────────────┘
```

### Design Principles

- **Defense in Depth**: Multiple layers of security validation
- **Configuration Flexibility**: Environment-based and file-based configuration
- **Developer Friendly**: Simple APIs with secure defaults
- **Production Ready**: Comprehensive error handling and logging
- **Cross-Platform**: Works on Windows, Linux, and macOS

## Core Components

### TLSConfig

The `TLSConfig` class provides centralized configuration management for all TLS operations.

**Location**: `pc_controller/src/network/tls_enhanced.py`

**Purpose**: 
- Manage TLS configuration parameters
- Load settings from environment variables or configuration files
- Validate certificate paths and settings
- Provide secure defaults

**Key Features**:
- Environment variable integration
- File-based configuration support
- Validation and error reporting
- Multiple verification modes

### SecureConnectionManager

The `SecureConnectionManager` handles all aspects of TLS connection establishment and management.

**Location**: `pc_controller/src/network/tls_enhanced.py`

**Purpose**:
- Create secure SSL contexts for servers and clients
- Wrap standard sockets with TLS encryption
- Manage connection lifecycle
- Handle TLS-specific errors

**Key Features**:
- Server and client SSL context creation
- Socket wrapping for transparent TLS
- Secure connection establishment
- Comprehensive error handling

### SecureMessageHandler

The `SecureMessageHandler` implements a secure message transmission protocol on top of TLS connections.

**Location**: `pc_controller/src/network/tls_enhanced.py`

**Purpose**:
- Send and receive messages securely
- Implement length-prefixed message framing
- Handle both TLS and plain text connections
- Provide message size validation

**Key Features**:
- Length-prefixed message protocol
- Support for both secure and plain sockets
- Message size limits and validation
- Cipher suite reporting

## Configuration Management

### Environment Variables

The TLS system supports configuration through environment variables for easy deployment:

| Variable | Default | Description |
|----------|---------|-------------|
| `PC_TLS_ENABLED` | `false` | Enable/disable TLS encryption |
| `PC_TLS_CERT_FILE` | `server.crt` | Path to server certificate file |
| `PC_TLS_KEY_FILE` | `server.key` | Path to server private key file |
| `PC_TLS_CA_FILE` | `ca.crt` | Path to CA certificate file |
| `PC_TLS_VERIFY_MODE` | `CERT_REQUIRED` | Certificate verification mode |
| `PC_TLS_CHECK_HOSTNAME` | `true` | Enable hostname verification |
| `PC_TLS_MIN_VERSION` | `TLSv1_2` | Minimum TLS version |
| `PC_TLS_CIPHERS` | `HIGH:!aNULL:!eNULL` | Allowed cipher suites |

### Configuration Examples

#### Development Environment

```bash
# Enable TLS with self-signed certificates
export PC_TLS_ENABLED=true
export PC_TLS_CERT_FILE=./certs/dev-server.crt
export PC_TLS_KEY_FILE=./certs/dev-server.key
export PC_TLS_VERIFY_MODE=CERT_NONE
export PC_TLS_CHECK_HOSTNAME=false
```

#### Production Environment

```bash
# Enable TLS with proper certificate chain
export PC_TLS_ENABLED=true
export PC_TLS_CERT_FILE=/etc/ssl/certs/platform-server.crt
export PC_TLS_KEY_FILE=/etc/ssl/private/platform-server.key
export PC_TLS_CA_FILE=/etc/ssl/certs/platform-ca.crt
export PC_TLS_VERIFY_MODE=CERT_REQUIRED
export PC_TLS_CHECK_HOSTNAME=true
export PC_TLS_MIN_VERSION=TLSv1_3
```

#### File-Based Configuration

```json
{
  "tls": {
    "enabled": true,
    "cert_file": "./certs/server.crt",
    "key_file": "./certs/server.key",
    "ca_file": "./certs/ca.crt",
    "verify_mode": "CERT_REQUIRED",
    "check_hostname": true,
    "min_version": "TLSv1_2",
    "ciphers": "HIGH:!aNULL:!eNULL:!EXPORT:!DES:!RC4:!MD5:!PSK"
  }
}
```

## API Reference

### TLSConfig Class

#### Constructor

```python
def __init__(
    self,
    enabled: bool = False,
    cert_file: str = "server.crt",
    key_file: str = "server.key",
    ca_file: str | None = None,
    verify_mode: str = "CERT_REQUIRED",
    check_hostname: bool = True,
    min_version: str = "TLSv1_2",
    ciphers: str = "HIGH:!aNULL:!eNULL:!EXPORT:!DES:!RC4:!MD5:!PSK"
)
```

**Parameters**:
- `enabled`: Whether TLS is enabled
- `cert_file`: Path to server certificate file
- `key_file`: Path to server private key file  
- `ca_file`: Path to CA certificate file (optional)
- `verify_mode`: Certificate verification mode
- `check_hostname`: Whether to verify hostname
- `min_version`: Minimum TLS version
- `ciphers`: Allowed cipher suites

#### Methods

##### from_env()

```python
@classmethod
def from_env(cls) -> 'TLSConfig'
```

Creates a TLSConfig instance from environment variables.

**Returns**: Configured TLSConfig instance

**Example**:
```python
config = TLSConfig.from_env()
print(f"TLS enabled: {config.enabled}")
```

##### from_file()

```python
@classmethod
def from_file(cls, config_file: str) -> 'TLSConfig'
```

Creates a TLSConfig instance from a JSON configuration file.

**Parameters**:
- `config_file`: Path to JSON configuration file

**Returns**: Configured TLSConfig instance

**Example**:
```python
config = TLSConfig.from_file("./config/tls.json")
```

##### validate()

```python
def validate(self) -> None
```

Validates the TLS configuration and raises exceptions for invalid settings.

**Raises**:
- `TLSConfigurationError`: If configuration is invalid
- `FileNotFoundError`: If certificate files don't exist

**Example**:
```python
config = TLSConfig.from_env()
try:
    config.validate()
    print("Configuration is valid")
except TLSConfigurationError as e:
    print(f"Configuration error: {e}")
```

### SecureConnectionManager Class

#### Constructor

```python
def __init__(self, config: TLSConfig)
```

**Parameters**:
- `config`: TLS configuration object

#### Methods

##### create_server_context()

```python
def create_server_context(self) -> ssl.SSLContext
```

Creates an SSL context configured for server use.

**Returns**: SSL context for server connections

**Raises**:
- `TLSConfigurationError`: If configuration is invalid
- `FileNotFoundError`: If certificate files are missing

**Example**:
```python
config = TLSConfig.from_env()
manager = SecureConnectionManager(config)
server_context = manager.create_server_context()
```

##### create_client_context()

```python
def create_client_context(self) -> ssl.SSLContext
```

Creates an SSL context configured for client use.

**Returns**: SSL context for client connections

**Example**:
```python
config = TLSConfig.from_env()
manager = SecureConnectionManager(config)
client_context = manager.create_client_context()
```

##### wrap_socket()

```python
def wrap_socket(
    self, 
    sock: socket.socket, 
    server_side: bool = False,
    server_hostname: str | None = None
) -> ssl.SSLSocket
```

Wraps a regular socket with TLS encryption.

**Parameters**:
- `sock`: Standard socket to wrap
- `server_side`: Whether this is the server side of the connection
- `server_hostname`: Hostname for client-side verification

**Returns**: TLS-wrapped socket

**Example**:
```python
# Server side
server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
secure_socket = manager.wrap_socket(server_socket, server_side=True)

# Client side
client_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
secure_socket = manager.wrap_socket(
    client_socket, 
    server_hostname="sensor-hub.local"
)
```

##### connect_secure()

```python
def connect_secure(
    self, 
    host: str, 
    port: int, 
    timeout: float = 30.0
) -> ssl.SSLSocket
```

Creates a secure client connection to the specified host and port.

**Parameters**:
- `host`: Target hostname or IP address
- `port`: Target port number
- `timeout`: Connection timeout in seconds

**Returns**: Established secure connection

**Raises**:
- `TLSConnectionError`: If connection fails
- `socket.timeout`: If connection times out

**Example**:
```python
config = TLSConfig.from_env()
manager = SecureConnectionManager(config)
secure_conn = manager.connect_secure("192.168.1.100", 8443)
```

### SecureMessageHandler Class

#### Constructor

```python
def __init__(self, connection_manager: SecureConnectionManager)
```

**Parameters**:
- `connection_manager`: Secure connection manager instance

#### Methods

##### send_secure_message()

```python
def send_secure_message(
    self,
    sock: socket.socket | ssl.SSLSocket,
    message: bytes,
    max_size: int = 1048576
) -> bool
```

Sends a message using length-prefixed framing over a secure connection.

**Parameters**:
- `sock`: Socket or SSL socket for transmission
- `message`: Message bytes to send
- `max_size`: Maximum allowed message size

**Returns**: True if message sent successfully, False otherwise

**Raises**:
- `TLSMessageError`: If message is too large or invalid
- `socket.error`: If network error occurs

**Example**:
```python
handler = SecureMessageHandler(manager)
message = b'{"command": "start_recording", "session_id": "test-123"}'
success = handler.send_secure_message(secure_socket, message)
```

##### receive_secure_message()

```python
def receive_secure_message(
    self,
    sock: socket.socket | ssl.SSLSocket,
    max_size: int = 1048576,
    timeout: float = 30.0
) -> bytes | None
```

Receives a length-prefixed message from a secure connection.

**Parameters**:
- `sock`: Socket or SSL socket for reception
- `max_size`: Maximum allowed message size
- `timeout`: Receive timeout in seconds

**Returns**: Received message bytes, or None if failed

**Raises**:
- `TLSMessageError`: If message format is invalid
- `socket.timeout`: If receive times out

**Example**:
```python
handler = SecureMessageHandler(manager)
received_data = handler.receive_secure_message(secure_socket)
if received_data:
    print(f"Received: {received_data.decode()}")
```

##### get_connection_info()

```python
def get_connection_info(self, sock: ssl.SSLSocket) -> dict
```

Returns detailed information about a TLS connection.

**Parameters**:
- `sock`: TLS socket to inspect

**Returns**: Dictionary with connection details

**Example**:
```python
info = handler.get_connection_info(secure_socket)
print(f"Cipher: {info['cipher']}")
print(f"Protocol: {info['version']}")
print(f"Peer certificate: {info['peer_cert_subject']}")
```

## Usage Examples

### Basic Server Setup

```python
import socket
from network.tls_enhanced import TLSConfig, SecureConnectionManager, SecureMessageHandler

# Configure TLS from environment
config = TLSConfig.from_env()
manager = SecureConnectionManager(config)
handler = SecureMessageHandler(manager)

# Create server socket
server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server_socket.bind(('0.0.0.0', 8443))
server_socket.listen(5)

if config.enabled:
    # Wrap with TLS
    secure_server = manager.wrap_socket(server_socket, server_side=True)
    print("TLS server listening on port 8443")
else:
    secure_server = server_socket
    print("Plain text server listening on port 8443")

while True:
    try:
        client_socket, addr = secure_server.accept()
        print(f"Connection from {addr}")
        
        # Receive message
        message = handler.receive_secure_message(client_socket)
        if message:
            print(f"Received: {message.decode()}")
            
            # Send response
            response = b'{"status": "ok", "message": "Command received"}'
            handler.send_secure_message(client_socket, response)
        
        client_socket.close()
        
    except Exception as e:
        print(f"Error handling client: {e}")
```

### Basic Client Setup

```python
from network.tls_enhanced import TLSConfig, SecureConnectionManager, SecureMessageHandler

# Configure TLS
config = TLSConfig.from_env()
manager = SecureConnectionManager(config)
handler = SecureMessageHandler(manager)

try:
    # Connect to server
    if config.enabled:
        secure_socket = manager.connect_secure("192.168.1.100", 8443)
        print("Secure connection established")
        
        # Show connection details
        info = handler.get_connection_info(secure_socket)
        print(f"Using {info['cipher']} with {info['version']}")
    else:
        secure_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        secure_socket.connect(("192.168.1.100", 8443))
        print("Plain text connection established")
    
    # Send command
    command = b'{"command": "query_capabilities"}'
    success = handler.send_secure_message(secure_socket, command)
    
    if success:
        # Receive response
        response = handler.receive_secure_message(secure_socket)
        if response:
            print(f"Response: {response.decode()}")
    
    secure_socket.close()
    
except Exception as e:
    print(f"Connection error: {e}")
```

### Integration with Heartbeat System

```python
from network.tls_enhanced import TLSConfig, SecureConnectionManager
from network.heartbeat_manager import HeartbeatManager
import asyncio

async def secure_heartbeat_demo():
    # Configure TLS
    config = TLSConfig.from_env()
    manager = SecureConnectionManager(config)
    
    # Create heartbeat manager with TLS support
    heartbeat_manager = HeartbeatManager(
        heartbeat_interval_s=3.0,
        timeout_multiplier=3,
        tls_manager=manager  # Pass TLS manager for secure connections
    )
    
    # Register device
    heartbeat_manager.register_device("android_device_1")
    
    # Set up callbacks
    def on_device_offline(device_id: str):
        print(f"Device {device_id} went offline - attempting secure reconnection")
    
    heartbeat_manager.set_device_offline_callback("android_device_1", on_device_offline)
    
    # Start monitoring with TLS
    await heartbeat_manager.start_monitoring()

# Run the demo
asyncio.run(secure_heartbeat_demo())
```

### Self-Signed Certificate Generation

```python
from network.tls_enhanced import generate_self_signed_cert
import os

# Create certificates directory
os.makedirs("./certs", exist_ok=True)

# Generate development certificates
cert_path, key_path = generate_self_signed_cert(
    hostname="sensor-hub.local",
    cert_file="./certs/dev-server.crt",
    key_file="./certs/dev-server.key",
    days_valid=365
)

print(f"Generated certificate: {cert_path}")
print(f"Generated private key: {key_path}")

# Update environment for development
os.environ['PC_TLS_ENABLED'] = 'true'
os.environ['PC_TLS_CERT_FILE'] = cert_path
os.environ['PC_TLS_KEY_FILE'] = key_path
os.environ['PC_TLS_VERIFY_MODE'] = 'CERT_NONE'
os.environ['PC_TLS_CHECK_HOSTNAME'] = 'false'

print("Development TLS environment configured")
```

## Security Features

### TLS Version Support

The implementation enforces modern TLS versions:

- **Minimum Version**: TLS 1.2 (configurable to TLS 1.3)
- **Disabled Versions**: SSL 2.0, SSL 3.0, TLS 1.0, TLS 1.1
- **Recommended**: TLS 1.3 for production environments

### Cipher Suite Selection

Secure cipher suites are enforced by default:

**Default Cipher String**: `HIGH:!aNULL:!eNULL:!EXPORT:!DES:!RC4:!MD5:!PSK`

**Excluded Ciphers**:
- Anonymous ciphers (`!aNULL`)
- NULL encryption (`!eNULL`)
- Export-grade ciphers (`!EXPORT`)
- DES encryption (`!DES`)
- RC4 stream cipher (`!RC4`)
- MD5 hash functions (`!MD5`)
- Pre-shared key ciphers (`!PSK`)

**Recommended Production Cipher String**:
```
ECDHE+AESGCM:ECDHE+CHACHA20:DHE+AESGCM:DHE+CHACHA20:!aNULL:!MD5:!DSS
```

### Certificate Validation

Multiple verification modes are supported:

| Mode | Description | Use Case |
|------|-------------|----------|
| `CERT_NONE` | No certificate verification | Development only |
| `CERT_OPTIONAL` | Verify if certificate present | Testing environments |
| `CERT_REQUIRED` | Always verify certificates | Production (recommended) |

### Hostname Verification

Hostname verification prevents man-in-the-middle attacks:

- **Enabled by default** in production configurations
- **Verifies** certificate Subject Alternative Names (SAN)
- **Supports** wildcard certificates
- **Can be disabled** for development environments

### Mutual Authentication

Support for mutual TLS (mTLS) authentication:

```python
# Configure client certificate authentication
config = TLSConfig(
    enabled=True,
    cert_file="server.crt",
    key_file="server.key",
    ca_file="client-ca.crt",  # CA that signed client certificates
    verify_mode="CERT_REQUIRED"
)
```

## Certificate Management

### Production Certificate Requirements

For production deployments, proper certificates are essential:

1. **Certificate Authority (CA)**: Use a trusted CA or internal PKI
2. **Subject Alternative Names**: Include all hostnames and IP addresses
3. **Key Length**: Minimum 2048-bit RSA or 256-bit ECC
4. **Validity Period**: Not more than 2 years
5. **Certificate Chain**: Include intermediate certificates

### Certificate File Formats

Supported certificate formats:

- **PEM Format**: Recommended, human-readable
- **DER Format**: Binary format (converted to PEM internally)
- **PKCS#12**: Contains both certificate and private key

### Example Certificate Generation (Production)

```bash
# Generate CA private key
openssl genrsa -out ca.key 4096

# Generate CA certificate
openssl req -new -x509 -days 3650 -key ca.key -out ca.crt \
    -subj "/C=US/ST=State/L=City/O=Organization/OU=IT/CN=Platform CA"

# Generate server private key
openssl genrsa -out server.key 2048

# Generate server certificate request
openssl req -new -key server.key -out server.csr \
    -subj "/C=US/ST=State/L=City/O=Organization/OU=IT/CN=sensor-hub.local"

# Create extension file for SAN
cat > server.ext << EOF
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage = digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment
subjectAltName = @alt_names

[alt_names]
DNS.1 = sensor-hub.local
DNS.2 = *.sensor-hub.local
IP.1 = 192.168.1.100
EOF

# Sign server certificate
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key \
    -CAcreateserial -out server.crt -days 365 -extensions v3_req \
    -extfile server.ext
```

### Certificate Rotation

Implement certificate rotation for production:

```python
import os
import time
from watchdog.observers import Observer
from watchdog.events import FileSystemEventHandler

class CertificateWatcher(FileSystemEventHandler):
    def __init__(self, config: TLSConfig, manager: SecureConnectionManager):
        self.config = config
        self.manager = manager
    
    def on_modified(self, event):
        if event.src_path in [self.config.cert_file, self.config.key_file]:
            print(f"Certificate file {event.src_path} updated")
            # Reload TLS context
            self.manager.reload_certificates()

# Set up certificate monitoring
observer = Observer()
observer.schedule(CertificateWatcher(config, manager), path="/etc/ssl/certs")
observer.start()
```

## Error Handling

### Exception Hierarchy

The TLS implementation defines a comprehensive exception hierarchy:

```python
class TLSError(Exception):
    """Base class for all TLS-related errors"""
    pass

class TLSConfigurationError(TLSError):
    """Raised when TLS configuration is invalid"""
    pass

class TLSConnectionError(TLSError):
    """Raised when TLS connection fails"""
    pass

class TLSMessageError(TLSError):
    """Raised when message handling fails"""
    pass

class TLSCertificateError(TLSError):
    """Raised when certificate validation fails"""
    pass
```

### Common Error Scenarios

#### Configuration Errors

```python
try:
    config = TLSConfig.from_env()
    config.validate()
except TLSConfigurationError as e:
    print(f"Configuration error: {e}")
    # Handle configuration problems
    if "cert_file" in str(e):
        print("Certificate file not found or invalid")
    elif "key_file" in str(e):
        print("Private key file not found or invalid")
```

#### Connection Errors

```python
try:
    secure_socket = manager.connect_secure("192.168.1.100", 8443)
except TLSConnectionError as e:
    print(f"TLS connection failed: {e}")
    # Handle connection problems
    if "certificate verify failed" in str(e):
        print("Certificate validation failed")
    elif "wrong version number" in str(e):
        print("TLS version mismatch")
except socket.timeout:
    print("Connection timed out")
except socket.error as e:
    print(f"Network error: {e}")
```

#### Message Handling Errors

```python
try:
    success = handler.send_secure_message(socket, message)
    if not success:
        print("Failed to send message")
except TLSMessageError as e:
    print(f"Message error: {e}")
    if "too large" in str(e):
        print("Message exceeds size limit")
except socket.error as e:
    print(f"Network error during message transmission: {e}")
```

### Error Recovery Strategies

#### Automatic Retry Logic

```python
import time
import random

def send_with_retry(handler, socket, message, max_retries=3):
    """Send message with exponential backoff retry logic"""
    for attempt in range(max_retries):
        try:
            success = handler.send_secure_message(socket, message)
            if success:
                return True
        except (TLSMessageError, socket.error) as e:
            if attempt == max_retries - 1:
                raise
            
            wait_time = (2 ** attempt) + random.uniform(0, 1)
            print(f"Attempt {attempt + 1} failed: {e}. Retrying in {wait_time:.2f}s")
            time.sleep(wait_time)
    
    return False
```

#### Connection Recovery

```python
def create_resilient_connection(manager, host, port, max_attempts=3):
    """Create connection with retry logic"""
    for attempt in range(max_attempts):
        try:
            return manager.connect_secure(host, port)
        except (TLSConnectionError, socket.error) as e:
            if attempt == max_attempts - 1:
                raise
            
            wait_time = 2 ** attempt
            print(f"Connection attempt {attempt + 1} failed: {e}")
            print(f"Retrying in {wait_time} seconds...")
            time.sleep(wait_time)
```

## Performance Considerations

### Connection Pooling

For high-throughput scenarios, implement connection pooling:

```python
import queue
import threading
from contextlib import contextmanager

class SecureConnectionPool:
    def __init__(self, manager: SecureConnectionManager, host: str, port: int, max_size: int = 10):
        self.manager = manager
        self.host = host
        self.port = port
        self.pool = queue.Queue(maxsize=max_size)
        self.lock = threading.Lock()
    
    @contextmanager
    def get_connection(self):
        """Get a connection from the pool"""
        conn = None
        try:
            # Try to get existing connection
            conn = self.pool.get_nowait()
        except queue.Empty:
            # Create new connection
            conn = self.manager.connect_secure(self.host, self.port)
        
        try:
            yield conn
        finally:
            # Return connection to pool
            try:
                self.pool.put_nowait(conn)
            except queue.Full:
                # Pool is full, close connection
                conn.close()

# Usage
pool = SecureConnectionPool(manager, "192.168.1.100", 8443)

with pool.get_connection() as conn:
    handler.send_secure_message(conn, message)
```

### SSL Session Reuse

Enable SSL session reuse for better performance:

```python
def create_optimized_client_context(self) -> ssl.SSLContext:
    """Create SSL context optimized for performance"""
    context = ssl.create_default_context()
    
    # Enable session reuse
    context.options |= ssl.OP_NO_COMPRESSION  # Disable compression for speed
    context.set_ciphers("ECDHE+AESGCM:ECDHE+CHACHA20")  # Fast ciphers
    
    # Set session cache
    context.session_stats()  # Initialize session cache
    
    return context
```

### Message Batching

Batch multiple small messages for better performance:

```python
def send_batched_messages(handler, socket, messages, batch_size=10):
    """Send multiple messages in batches"""
    import json
    
    for i in range(0, len(messages), batch_size):
        batch = messages[i:i + batch_size]
        
        # Create batch message
        batch_message = {
            "type": "batch",
            "messages": batch
        }
        
        batch_data = json.dumps(batch_message).encode()
        handler.send_secure_message(socket, batch_data)
```

### Memory Management

Optimize memory usage for large message handling:

```python
def send_large_message(handler, socket, data, chunk_size=8192):
    """Send large messages in chunks"""
    import struct
    
    # Send total size first
    total_size = len(data)
    size_header = struct.pack("!Q", total_size)
    socket.sendall(size_header)
    
    # Send data in chunks
    for i in range(0, total_size, chunk_size):
        chunk = data[i:i + chunk_size]
        socket.sendall(chunk)
```

## Troubleshooting

### Common Issues and Solutions

#### Issue: "certificate verify failed"

**Symptoms**: TLS connection fails with certificate verification error

**Causes**:
- Invalid certificate chain
- Expired certificates
- Hostname mismatch
- Missing CA certificates

**Solutions**:
```python
# Check certificate validity
from cryptography import x509
from cryptography.hazmat.backends import default_backend
import datetime

def check_certificate(cert_file):
    with open(cert_file, 'rb') as f:
        cert_data = f.read()
    
    cert = x509.load_pem_x509_certificate(cert_data, default_backend())
    
    # Check expiration
    now = datetime.datetime.now()
    if cert.not_valid_after < now:
        print(f"Certificate expired on {cert.not_valid_after}")
    
    # Check hostname
    try:
        san_ext = cert.extensions.get_extension_for_oid(x509.oid.ExtensionOID.SUBJECT_ALTERNATIVE_NAME)
        hostnames = san_ext.value.get_values_for_type(x509.DNSName)
        print(f"Certificate valid for hostnames: {hostnames}")
    except x509.ExtensionNotFound:
        print("No Subject Alternative Names found")

check_certificate("server.crt")
```

#### Issue: "wrong version number"

**Symptoms**: TLS handshake fails with version error

**Causes**:
- TLS version mismatch between client and server
- Plain text connection to TLS port
- Firewall interference

**Solutions**:
```python
# Test TLS connectivity
import ssl
import socket

def test_tls_connectivity(host, port):
    """Test TLS connectivity with different versions"""
    versions = [
        ("TLS 1.2", ssl.PROTOCOL_TLSv1_2),
        ("TLS 1.3", ssl.PROTOCOL_TLS),
    ]
    
    for version_name, protocol in versions:
        try:
            context = ssl.SSLContext(protocol)
            with socket.create_connection((host, port), timeout=10) as sock:
                with context.wrap_socket(sock, server_hostname=host) as ssock:
                    print(f"{version_name}: Connected successfully")
                    print(f"  Cipher: {ssock.cipher()}")
                    print(f"  Version: {ssock.version()}")
        except Exception as e:
            print(f"{version_name}: Failed - {e}")

test_tls_connectivity("192.168.1.100", 8443)
```

#### Issue: "Connection refused" or timeouts

**Symptoms**: Cannot establish TLS connection

**Causes**:
- Server not running
- Port blocked by firewall
- Network connectivity issues
- Wrong host/port configuration

**Solutions**:
```python
# Diagnostic connection test
import socket
import time

def diagnose_connection(host, port):
    """Diagnose connection issues"""
    print(f"Testing connection to {host}:{port}")
    
    # Test basic connectivity
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(5)
        result = sock.connect_ex((host, port))
        if result == 0:
            print("✓ Port is open")
        else:
            print("✗ Port is closed or filtered")
        sock.close()
    except socket.gaierror:
        print("✗ Hostname resolution failed")
    
    # Test TLS handshake
    try:
        context = ssl.create_default_context()
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        
        with socket.create_connection((host, port), timeout=10) as sock:
            with context.wrap_socket(sock) as ssock:
                print("✓ TLS handshake successful")
                print(f"  Protocol: {ssock.version()}")
    except Exception as e:
        print(f"✗ TLS handshake failed: {e}")

diagnose_connection("192.168.1.100", 8443)
```

### Debugging Tools

#### TLS Connection Inspector

```python
def inspect_tls_connection(socket):
    """Inspect TLS connection details"""
    if isinstance(socket, ssl.SSLSocket):
        print("TLS Connection Details:")
        print(f"  Version: {socket.version()}")
        print(f"  Cipher: {socket.cipher()}")
        print(f"  Compression: {socket.compression()}")
        
        # Certificate chain
        cert_chain = socket.getpeercert_chain()
        if cert_chain:
            print(f"  Certificate chain length: {len(cert_chain)}")
            for i, cert in enumerate(cert_chain):
                subject = cert.get_subject()
                print(f"    Cert {i}: {subject}")
        
        # Session info
        session = socket.session
        if session:
            print(f"  Session ID: {session.id.hex()}")
            print(f"  Session reused: {session.session_reused}")
    else:
        print("Plain text connection (no TLS)")
```

#### Performance Monitor

```python
import time
import contextlib

@contextlib.contextmanager
def tls_performance_monitor():
    """Monitor TLS operation performance"""
    start_time = time.perf_counter()
    start_memory = get_memory_usage()
    
    try:
        yield
    finally:
        end_time = time.perf_counter()
        end_memory = get_memory_usage()
        
        duration = end_time - start_time
        memory_delta = end_memory - start_memory
        
        print(f"TLS operation completed in {duration:.3f}s")
        print(f"Memory usage: {memory_delta:+.2f} MB")

def get_memory_usage():
    """Get current memory usage in MB"""
    import psutil
    process = psutil.Process()
    return process.memory_info().rss / (1024 * 1024)

# Usage
with tls_performance_monitor():
    secure_socket = manager.connect_secure("192.168.1.100", 8443)
    handler.send_secure_message(secure_socket, message)
```

### Log Analysis

Enable comprehensive logging for troubleshooting:

```python
import logging
import ssl

# Configure SSL debugging
logging.basicConfig(level=logging.DEBUG)
logger = logging.getLogger('tls')

# Enable SSL debug output
ssl._create_default_https_context = ssl._create_unverified_context
ssl.match_hostname = lambda cert, hostname: True  # Development only!

# Custom log formatter for TLS events
class TLSFormatter(logging.Formatter):
    def format(self, record):
        if hasattr(record, 'tls_info'):
            return f"{super().format(record)} | TLS: {record.tls_info}"
        return super().format(record)

handler = logging.StreamHandler()
handler.setFormatter(TLSFormatter())
logger.addHandler(handler)
```

This comprehensive TLS API documentation provides complete coverage of the TLS security implementation, from basic usage to advanced troubleshooting scenarios. The documentation includes practical examples, security best practices, and detailed troubleshooting guides to ensure successful deployment in production environments.