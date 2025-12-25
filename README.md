# 🏗️ Kiến Trúc Hệ Thống - Lab Platform

## 📋 Mục lục

- [Tổng quan](#tổng-quan)
- [Kiến trúc tổng thể](#kiến-trúc-tổng-thể)
- [Chi tiết các thành phần](#chi-tiết-các-thành-phần)
- [WebSocket Architecture](#websocket-architecture)
- [Authentication & Authorization](#authentication--authorization)
- [Kubernetes Integration](#kubernetes-integration)
- [Database Schema](#database-schema)
- [Messaging System](#messaging-system)

---

## 🎯 Tổng quan

Hệ thống Lab Platform là một nền tảng giáo dục lập trình sử dụng kiến trúc microservices, cho phép:

- Sinh viên thực hành lập trình trên môi trường VM ảo hóa
- Giảng viên quản lý bài lab, theo dõi tiến độ
- Admin quản lý hệ thống, test lab configurations

### Tech Stack

- **Backend**: Java Spring Boot (Microservices)
- **Frontend**: React TypeScript + Vite
- **API Gateway**: Spring Cloud Gateway
- **Database**: PostgreSQL
- **Message Queue**: Apache Kafka
- **Orchestration**: Kubernetes + KubeVirt
- **Storage**: Longhorn
- **Real-time**: WebSocket + SSH

---

## 🏛️ Kiến trúc tổng thể

```
┌────────────────────────────────────────────────────────────────┐
│                        FRONTEND LAYER                          │
│                     React TypeScript App                       │
│                      (Port 3000/5173)                          │
└────────────┬───────────────────────────────────────────────────┘
             │
             │ HTTP/WebSocket
             │
┌────────────▼───────────────────────────────────────────────────┐
│                    API GATEWAY LAYER                           │
│              Spring Cloud Gateway (Port 8082)                  │
│  ┌──────────────────────────────────────────────────────┐     │
│  │  • Authentication Filter (JWT validation)            │     │
│  │  • Route Management (/api/**, /ws/**)               │     │
│  │  • CORS Configuration                                │     │
│  │  • WebSocket Transparent Proxy                       │     │
│  └──────────────────────────────────────────────────────┘     │
└────────────┬────────────────────────┬──────────────────────────┘
             │                        │
        HTTP │                        │ WebSocket
             │                        │
    ┌────────▼────────┐      ┌───────▼──────────────┐
    │                 │      │                      │
    │   CMS Backend   │      │ Infrastructure Svc   │
    │   (Port 8080)   │      │    (Port 8081)       │
    │                 │      │                      │
    └────────┬────────┘      └───────┬──────────────┘
             │                       │
             │                       │
    ┌────────▼────────┐      ┌───────▼──────────────┐
    │   PostgreSQL    │      │    Kafka Cluster     │
    │   Database      │      │  (Port 9092)         │
    └─────────────────┘      └───────┬──────────────┘
                                     │
                             ┌───────▼──────────────┐
                             │  Kubernetes Cluster  │
                             │    + KubeVirt        │
                             │    + Longhorn        │
                             │    + Calico          │
                             └──────────────────────┘
```

---

## 🔧 Chi tiết các thành phần

### 1. **Frontend Application**

**Technology**: React 18 + TypeScript + Vite

**Port**: 3000 (development), 5173 (Vite dev server)

**Main Features**:

- 🎓 Student Dashboard (Lab list, Progress tracking, Leaderboard)
- 👨‍🏫 Instructor Dashboard (Course management, Student monitoring)
- 👨‍💼 Admin Panel (Lab testing, System configuration)
- 🖥️ Interactive Terminal (WebSocket-based SSH terminal)
- 📊 Real-time Progress (WebSocket updates during VM creation)

**Key Libraries**:

- `shadcn/ui` + `Tailwind CSS` - UI Components
- `React Router` - Routing
- `Redux Toolkit` - State management
- `xterm.js` - Terminal emulator
- `native WebSocket API` - Real-time communication

**Directory Structure**:

```
src/
├── components/        # Reusable UI components
├── pages/            # Page components
├── services/         # API & WebSocket services
├── hooks/            # Custom React hooks
├── types/            # TypeScript type definitions
└── lib/              # Utilities & API client
```

---

### 2. **Spring Cloud Gateway**

**Technology**: Spring Cloud Gateway (Reactive)

**Port**: 8082

**Responsibilities**:

- ✅ **Centralized Authentication** (JWT validation)
- 🔀 **Request Routing** (HTTP & WebSocket)
- 🌐 **CORS Handling**
- 🔒 **Security Enforcement**
- 🔄 **WebSocket Transparent Proxy**

**Route Configuration**:

```java
// HTTP Routes
/api/auth/**        → CMS Backend (no auth)
/api/**             → CMS Backend (with auth)

// WebSocket Routes
/ws/**              → Infrastructure Service (with auth)
```

**Authentication Filter**:

- HTTP Requests: Token from `Authorization: Bearer <token>` header
- WebSocket Requests: Token from `?token=<token>` query parameter
- Validates JWT and adds `X-User-Id`, `X-Username` headers

**Important**: Gateway does NOT terminate WebSocket - it acts as a **transparent proxy** forwarding frames between frontend and backend.

---

### 3. **CMS Backend Service**

**Technology**: Spring Boot 3 + JPA/Hibernate

**Port**: 8080

**Database**: PostgreSQL

**Responsibilities**:

- 📚 **Course & Lab Management** (CRUD operations)
- 👥 **User Management** (Students, Instructors, Admins)
- 🎯 **Question & Answer Management**
- 📊 **Progress Tracking** (Lab sessions, Submissions)
- 🏆 **Leaderboard & Statistics**
- 🔐 **JWT Authentication** (Login, Token refresh)
- 📨 **Kafka Producer** (Send lab creation requests)

**Main Entities**:

```
User (Student/Instructor/Admin)
  ↓
Course
  ↓
Lab (Template)
  ├── InstanceType (CPU, RAM, Storage, BackingImage)
  ├── SetupSteps (Commands to run on VM)
  └── Questions
       └── Answers
  ↓
UserLabSession (Student's lab instance)
  ├── Submissions (Answers to questions)
  └── AttemptHistory (Tracking retries)
```

**Authentication**:

- Spring Security + JWT (Nimbus JOSE)
- Two-layer validation:
  1. Gateway validates token
  2. Backend validates again and sets SecurityContext

**Key Services**:

- `LabService` - Lab template management
- `UserLabSessionService` - Student lab sessions
- `LeaderboardService` - Ranking calculations
- `VMTestService` - Admin lab testing
- `VMUserSessionService` - Student VM creation

---

### 4. **Infrastructure Service**

**Technology**: Spring Boot 3 + Kubernetes Client

**Port**: 8081

**Responsibilities**:

- 🖥️ **VM Creation & Management** (via KubeVirt)
- 📡 **WebSocket Servers** (3 endpoints)
- 🔌 **SSH Connection Management** (JSch library)
- 📨 **Kafka Consumer** (Receive lab creation requests)
- 🔧 **Setup Script Execution** (SSH commands)
- 🌐 **Kubernetes API Integration**

**Key Components**:

#### **WebSocket Endpoints**:

1. `/ws/pod-logs` - VM creation progress (Student & Admin)
2. `/ws/terminal/{labSessionId}` - Interactive SSH terminal (Student only)
3. `/ws/admin/test-lab` - Admin testing (deprecated, merged into pod-logs)

#### **Services**:

- `VMService` - Kubernetes resource creation (VM, DataVolume, NetworkPolicy)
- `VMUserSessionService` - Student VM lifecycle (create → setup → ready)
- `VMTestService` - Admin VM testing
- `KubernetesDiscoveryService` - Pod monitoring & waiting
- `SetupExecutionService` - SSH command execution
- `SshSessionCache` - SSH connection pooling
- `TerminalSessionService` - WebSocket session management

**Kubernetes Resources Created**:

```yaml
VirtualMachine (KubeVirt)
├── spec.template.spec.domain
│   ├── cpu.cores
│   ├── memory
│   └── devices.disks
├── spec.dataVolumeTemplates
│   └── DataVolume (from backing image)
└── NetworkPolicy (Calico)
└── bandwidth limiting
```

---

## 🔌 WebSocket Architecture

### **Overview**

Hệ thống sử dụng 2 loại WebSocket connections với mục đích khác nhau:

```
┌─────────────────────────────────────────────────────────────┐
│                  WebSocket Connection Types                  │
├─────────────────────────────────────────────────────────────┤
│  1. VM Creation Progress (/ws/pod-logs)                     │
│     - One-way: Backend → Frontend                           │
│     - Purpose: Show VM creation steps                       │
│     - Used by: Students & Admins                            │
│                                                              │
│  2. Interactive Terminal (/ws/terminal/{labSessionId})      │
│     - Bidirectional: Frontend ↔ Backend ↔ SSH              │
│     - Purpose: Execute commands on VM                       │
│     - Used by: Students only                                │
└─────────────────────────────────────────────────────────────┘
```

---

### **WebSocket 1: Pod Logs (`/ws/pod-logs`)**

**Endpoint**: `ws://localhost:8082/ws/pod-logs?podName=<vmName>&token=<jwt>`

**Handler**: `PodLogWebSocketHandler` (Infrastructure Service)

**Purpose**: Real-time updates during VM creation process

**Flow**:

```
┌──────────┐                                          ┌──────────────┐
│ Frontend │                                          │  Infra Svc   │
└────┬─────┘                                          └──────┬───────┘
     │                                                        │
     │ 1. Connect WebSocket                                  │
     ├───────────────────────────────────────────────────────>│
     │   ws://gateway:8082/ws/pod-logs?podName=vm-1&token=.. │
     │                                                        │
     │ 2. Gateway validates token & proxies                  │
     │    Gateway ────────────────────────────────────>      │
     │                                                        │
     │ 3. WebSocket handshake successful                     │
     │<───────────────────────────────────────────────────────┤
     │   HTTP 101 Switching Protocols                        │
     │                                                        │
     │                                                        │
     │ 4. Backend starts VM creation                         │
     │                                                        │
     │<═══════════════════════════════════════════════════════┤
     │   { "type": "info", "message": "Creating VM..." }     │
     │                                                        │
     │<═══════════════════════════════════════════════════════┤
     │   { "type": "progress", "message": "Pod ready" }      │
     │                                                        │
     │<═══════════════════════════════════════════════════════┤
     │   { "type": "success", "message": "VM created!" }     │
     │                                                        │
     │<═══════════════════════════════════════════════════════┤
     │   { "type": "terminal_ready",                         │
     │     "data": { "labSessionId": 123 } }                 │
     │                                                        │
     │ 5. Frontend shows terminal UI                         │
     │                                                        │
```

**Message Types**:

```typescript
{
  "type": "connection" | "info" | "progress" | "success" |
          "error" | "warning" | "terminal_ready",
  "message": string,
  "data": {
    "currentStep"?: number,
    "totalSteps"?: number,
    "percentage"?: number,
    "labSessionId"?: number
  },
  "timestamp": number
}
```

**Connection Lifecycle**:

1. Frontend connects (query: `podName`, `token`)
2. Backend validates & stores session in `podSessions` Map
3. Backend broadcasts messages to specific `podName`
4. Multiple reconnections supported (WebSocket is ephemeral)
5. Connection closed when frontend navigates away

---

### **WebSocket 2: Interactive Terminal (`/ws/terminal/{labSessionId}`)**

**Endpoint**: `ws://localhost:8082/ws/terminal/123?token=<jwt>`

**Handler**: `TerminalHandler` (Infrastructure Service - DEPRECATED after refactor)

**Current Implementation**: Uses `PodLogWebSocketHandler` with persistent terminal sessions

**Purpose**: Bidirectional SSH terminal access

**Flow**:

```
┌──────────┐          ┌─────────┐          ┌──────────────┐          ┌─────────┐
│ Frontend │          │ Gateway │          │  Infra Svc   │          │   VM    │
└────┬─────┘          └────┬────┘          └──────┬───────┘          └────┬────┘
     │                     │                       │                       │
     │ 1. Receive terminal_ready from pod-logs    │                       │
     │<════════════════════════════════════════════┤                       │
     │                     │                       │                       │
     │ 2. Terminal already setup (SSH cached)     │                       │
     │                     │                       │                       │
     │ 3. User types command                       │                       │
     ├─────────────────────────────────────────────>│                       │
     │   TextMessage: "ls -la\n"                   │                       │
     │                     │                       │                       │
     │                     │                       │ 4. Forward to SSH     │
     │                     │                       ├──────────────────────>│
     │                     │                       │   OutputStream.write()│
     │                     │                       │                       │
     │                     │                       │ 5. Read output        │
     │                     │                       │<──────────────────────┤
     │                     │                       │   InputStream.read()  │
     │                     │                       │                       │
     │ 6. Display output   │                       │                       │
     │<═════════════════════════════════════════════┤                       │
     │   TextMessage: "total 48\ndrwxr-xr-x..."   │                       │
     │                     │                       │                       │
```

**SSH Connection Management**:

```
PodLogWebSocketHandler maintains:

1. WebSocket Sessions (Ephemeral)
   Map<podName, WebSocketSession> podSessions
   - Removed on disconnect
   - Can reconnect

2. Terminal Sessions (Persistent)
   Map<podName, TerminalSessionData> terminalSessions
   - Survives WebSocket disconnects
   - Contains:
     • SSH ChannelShell
     • InputStream/OutputStream
     • Output reader thread
     • Active flag
   - Only cleaned up on:
     • Explicit cleanup call (Kafka event)
     • Application shutdown
```

**Connection Pre-establishment**:

```java
// Before WebSocket connection, SSH is pre-connected:
VMUserSessionService.preConnectAndCacheSSH()
  ↓
1. Port-forward to pod (Kubernetes API)
2. Create JSch SSH session
3. Store in SshSessionCache
  ↓
Later, when WebSocket connects:
PodLogWebSocketHandler.setupTerminal()
  ↓
1. Retrieve cached SSH session
2. Open shell channel
3. Start output reader thread
4. Store TerminalSessionData
  ↓
Result: Zero retry, instant terminal!
```

**Key Improvement**:

- ❌ Old: 7-8 SSH connection retries when user opens terminal
- ✅ New: Pre-established SSH, instant connection

---

### **WebSocket Connection States**

```
┌─────────────────────────────────────────────────────────────┐
│              Connection State Diagram                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  [Disconnected]                                             │
│       │                                                      │
│       │ connect()                                           │
│       ▼                                                      │
│  [Connecting] ──error──> [Error]                           │
│       │                                                      │
│       │ onopen()                                            │
│       ▼                                                      │
│  [Connected] ◄──────┐                                       │
│       │             │ reconnect                             │
│       │             │                                        │
│       │ onmessage() │                                       │
│       │             │                                        │
│       │ onclose()   │                                       │
│       ▼             │                                        │
│  [Disconnected] ────┘                                       │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

### **WebSocket Message Routing**

**Gateway as Transparent Proxy**:

```
Frontend                   Gateway                   Backend
   │                         │                         │
   │──── WS Frame ──────────>│                         │
   │   (opcode: text)        │──── Forward ──────────>│
   │                         │   (same frame)          │
   │                         │                         │
   │                         │<──── Response ──────────┤
   │<──── Forward ───────────┤                         │
   │   (same frame)          │                         │
```

**Important**:

- Gateway does NOT inspect frame payload
- Gateway does NOT modify frames
- Gateway simply proxies at TCP/WebSocket layer
- Gateway maintains TWO TCP connections:
  1. Frontend ↔ Gateway (Port 8082)
  2. Gateway ↔ Backend (Port 8081)

---

## 🔐 Authentication & Authorization

### **Authentication Flow**

```
┌──────────┐                                              ┌─────────────┐
│ Frontend │                                              │ CMS Backend │
└────┬─────┘                                              └──────┬──────┘
     │                                                            │
     │ 1. POST /api/auth/login                                   │
     │    { username, password }                                 │
     ├───────────────────────────────────────────────────────────>│
     │                                                            │
     │                                        2. Authenticate     │
     │                                           (Spring Security)│
     │                                                            │
     │ 3. Return JWT tokens                                      │
     │<───────────────────────────────────────────────────────────┤
     │    { accessToken, refreshToken, user info }               │
     │                                                            │
     │ 4. Store tokens in localStorage                           │
     │                                                            │
     │                                                            │
     │ 5. Subsequent requests                                    │
     │    Authorization: Bearer <accessToken>                    │
     ├───────────────────────────────────────────────────────────>│
     │                                                            │
```

### **JWT Token Structure**

```json
{
  "sub": "username",
  "id": 123,
  "email": "user@example.com",
  "firstName": "John",
  "lastName": "Doe",
  "roles": ["ROLE_STUDENT"],
  "iss": "labplatform",
  "iat": 1234567890,
  "exp": 1234571490
}
```

### **Token Validation Layers**

```
Layer 1: Spring Cloud Gateway (Port 8082)
├─ AuthenticationFilter
├─ Validates JWT signature & expiration
├─ Extracts user info
└─ Adds headers: X-User-Id, X-Username

Layer 2: CMS Backend (Port 8080)
├─ AuthTokenFilter
├─ Validates token again (defense in depth)
├─ Loads UserDetails
└─ Sets SecurityContext (for @PreAuthorize)

Layer 3: Infrastructure Service (Port 8081)
└─ NO authentication layer (trusts Gateway)
```

### **Authorization Matrix**

| Resource              | Student | Instructor | Admin |
| --------------------- | ------- | ---------- | ----- |
| View active labs      | ✅      | ✅         | ✅    |
| View all labs         | ❌      | ❌         | ✅    |
| Create lab            | ❌      | ✅         | ✅    |
| Edit lab              | ❌      | ✅         | ✅    |
| Delete lab            | ❌      | ❌         | ✅    |
| Start lab session     | ✅      | ❌         | ❌    |
| View leaderboard      | ✅      | ✅         | ✅    |
| Test lab              | ❌      | ❌         | ✅    |
| View student progress | ❌      | ✅         | ✅    |

---

## ☸️ Kubernetes Integration

### **Kubernetes Resources**

```
Namespace: default (configurable)
  │
  ├─ VirtualMachine (KubeVirt CRD)
  │   ├─ Metadata: labels, annotations
  │   ├─ Spec:
  │   │   ├─ Running: true
  │   │   ├─ Template:
  │   │   │   ├─ Domain:
  │   │   │   │   ├─ CPU: cores
  │   │   │   │   ├─ Memory: size
  │   │   │   │   └─ Devices: disks, interfaces
  │   │   │   └─ Networks: pod network
  │   │   └─ DataVolumeTemplates:
  │   │       └─ PVC → Backing Image
  │   └─ Status: ready, phase
  │
  ├─ DataVolume (CDI CRD)
  │   ├─ Source: PVC (backing image)
  │   ├─ PVC: storage class, size
  │   └─ Status: progress
  │
  ├─ NetworkPolicy (Calico)
  │   ├─ Egress: allow internet, block cluster
  │   ├─ Ingress: deny all
  │   └─ Annotations: bandwidth limiting
  │
  └─ Pod (Created by KubeVirt)
      ├─ virt-launcher-<vm-name>
      ├─ Containers: compute, virt-launcher
      └─ Status: Running, Ready
```

### **VM Creation Process**

```
┌────────────────────────────────────────────────────────────┐
│              VM Creation Steps (Kubernetes)                 │
├────────────────────────────────────────────────────────────┤
│                                                             │
│ 1. Create DataVolume (Clone backing image)                 │
│    └─> CDI controller provisions PVC                       │
│    └─> Clone image → PVC (5-30 seconds)                    │
│                                                             │
│ 2. Create NetworkPolicy (Calico)                           │
│    └─> Apply egress rules                                  │
│    └─> Set bandwidth limit (annotations)                   │
│                                                             │
│ 3. Create VirtualMachine                                   │
│    └─> KubeVirt controller creates Pod                     │
│    └─> virt-launcher container starts                      │
│    └─> QEMU/KVM launches VM                                │
│                                                             │
│ 4. Wait for Pod Running (up to 10 minutes)                 │
│    └─> Poll Kubernetes API                                 │
│    └─> Check pod.status.phase == "Running"                 │
│                                                             │
│ 5. Wait for SSH ready (port 22)                            │
│    └─> Kubernetes port-forward                             │
│    └─> JSch connection test                                │
│                                                             │
│ 6. Execute setup steps (if any)                            │
│    └─> SSH commands via port-forward                       │
│                                                             │
│ 7. Terminal ready                                          │
│    └─> Pre-establish SSH connection                        │
│    └─> Cache session for instant terminal                  │
│                                                             │
└────────────────────────────────────────────────────────────┘
```

### **Kubernetes API Client Configuration**

**ServiceAccount**: `cms-backend-sa` (namespace: default)

**Permissions** (RBAC):

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: cms-backend-role
rules:
  - apiGroups: ["kubevirt.io"]
    resources: ["virtualmachines", "virtualmachineinstances"]
    verbs: ["get", "list", "create", "delete"]

  - apiGroups: ["cdi.kubevirt.io"]
    resources: ["datavolumes"]
    verbs: ["get", "list", "create", "delete"]

  - apiGroups: [""]
    resources: ["pods", "pods/log", "pods/portforward"]
    verbs: ["get", "list", "watch"]

  - apiGroups: ["networking.k8s.io"]
    resources: ["networkpolicies"]
    verbs: ["create", "delete"]
```

**Timeout Configuration**:

```properties
# Standard operations
kubernetes.timeout.default=30s

# Long operations (VM creation)
kubernetes.timeout.long=600s
```

---

## 🗄️ Database Schema

### **Core Entities**

```sql
-- Users & Authentication
users
├─ id (PK)
├─ username (unique)
├─ email (unique)
├─ password (bcrypt)
├─ first_name
├─ last_name
├─ role_id (FK → roles)
└─ is_active

roles
├─ id (PK)
├─ name (ROLE_STUDENT, ROLE_INSTRUCTOR, ROLE_ADMIN)
└─ permissions

-- Course Structure
courses
├─ id (PK)
├─ title
├─ description
├─ instructor_id (FK → users)
└─ is_active

labs
├─ id (PK)
├─ course_id (FK → courses)
├─ title
├─ description
├─ namespace (Kubernetes)
├─ estimated_time
├─ instance_type_id (FK → instance_types)
└─ is_active

instance_types
├─ id (PK)
├─ backing_image (e.g., "ubuntu-22.04")
├─ cpu_cores (e.g., 2)
├─ memory_gb (e.g., 4)
└─ storage_gb (e.g., 20)

setup_steps
├─ id (PK)
├─ lab_id (FK → labs)
├─ step_order
├─ title
├─ setup_command
├─ expected_exit_code (default: 0)
├─ timeout_seconds (default: 300)
├─ retry_count (default: 3)
└─ continue_on_failure (default: false)

questions
├─ id (PK)
├─ lab_id (FK → labs)
├─ question_text
├─ hint
├─ solution
└─ question_order

answers
├─ id (PK)
├─ question_id (FK → questions)
├─ content
└─ is_right_ans

-- Student Sessions
user_lab_sessions
├─ id (PK)
├─ user_id (FK → users)
├─ lab_id (FK → labs)
├─ vm_name (Kubernetes VM name)
├─ start_time
├─ end_time
├─ status (PENDING, RUNNING, COMPLETED, FAILED)
└─ score

submissions
├─ id (PK)
├─ user_lab_session_id (FK → user_lab_sessions)
├─ question_id (FK → questions)
├─ answer_id (FK → answers)
└─ submitted_at

attempt_history
├─ id (PK)
├─ user_lab_session_id (FK → user_lab_sessions)
├─ question_id (FK → questions)
├─ attempt_number
├─ is_correct
└─ attempted_at
```

### **Leaderboard Query Logic**

```java
// Pseudo-code for leaderboard calculation
SELECT
  uls.user_id,
  COUNT(DISTINCT uls.lab_id) as completed_labs,
  SUM(
    CASE
      WHEN first_try THEN 100
      WHEN second_try THEN 90
      ELSE 100 - (retry_penalty * retry_count)
    END
  ) as total_score,
  AVG(completion_time) as avg_time
FROM user_lab_sessions uls
WHERE uls.status = 'COMPLETED'
GROUP BY uls.user_id
ORDER BY total_score DESC, avg_time ASC
```

**Scoring Formula**:

- First attempt success: +100 points
- Second attempt success: +90 points
- Third+ attempt: Base 100 - (10 × retry_count)
- Fast completion: Bonus multiplier
- Lab difficulty: Weight multiplier

---

## 📨 Messaging System (Apache Kafka)

### **Topics & Messages**

```
┌──────────────────────────────────────────────────────────┐
│                  Kafka Topic Structure                    │
├──────────────────────────────────────────────────────────┤
│                                                           │
│  Topic 1: lab-test-requests                              │
│  ├─ Producer: CMS Backend (VMTestService)               │
│  ├─ Consumer: Infrastructure Service                     │
│  ├─ Purpose: Admin lab testing                          │
│  └─ Message:                                             │
│      {                                                    │
│        "labId": 123,                                     │
│        "testVmName": "test-vm-123-1234567890",          │
│        "namespace": "default",                           │
│        "title": "Linux Basics Lab",                     │
│        "instanceType": {                                 │
│          "backingImage": "ubuntu-22.04",                │
│          "cpuCores": 2,                                  │
│          "memoryGb": 4,                                  │
│          "storageGb": 20                                 │
│        },                                                │
│        "setupStepsJson": "[{...}]"                      │
│      }                                                    │
│                                                           │
│  Topic 2: user-lab-session-requests                      │
│  ├─ Producer: CMS Backend (UserLabSessionService)       │
│  ├─ Consumer: Infrastructure Service                     │
│  ├─ Purpose: Student lab sessions                       │
│  └─ Message:                                             │
│      {                                                    │
│        "labSessionId": 456,                             │
│        "userId": 789,                                    │
│        "labId": 123,                                     │
│        "vmName": "student-vm-456",                      │
│        "namespace": "default",                           │
│        "instanceType": {...},                           │
│        "setupStepsJson": null // No setup for students  │
│      }                                                    │
│                                                           │
└──────────────────────────────────────────────────────────┘
```

### **Why Kafka?**

1. **Async Processing**: VM creation takes 2-10 minutes, too slow for HTTP
2. **Decoupling**: CMS Backend doesn't wait for VM creation
3. **Reliability**: Messages persist if Infrastructure Service is down
4. **Scalability**: Can add multiple Infrastructure Service instances

### **Message Flow Example**

```
Student clicks "Start Lab"
   │
   ▼
CMS Backend:
   ├─ Create UserLabSession (status: PENDING)
   ├─ Send Kafka message → user-lab-session-requests
   └─ Return HTTP 200 (immediate response)
   │
   ▼
Kafka Broker:
   └─ Store message
   │
   ▼
Infrastructure Service:
   ├─ Consume message
   ├─ Create Kubernetes resources
   ├─ WebSocket updates → Frontend
   └─ Update UserLabSession (status: RUNNING)
   │
   ▼
Student sees:
   └─ Real-time progress via WebSocket
```

---

## 🔄 Complete User Flows

### **Flow 1: Student Starts Lab**

```
┌─────────┐    ┌─────────┐    ┌─────────┐    ┌──────────┐    ┌────────┐
│Frontend │    │ Gateway │    │CMS BE   │    │  Kafka   │    │Infra Svc│
└────┬────┘    └────┬────┘    └────┬────┘    └────┬─────┘    └────┬───┘
     │              │              │              │               │
  1. │ POST /api/user-lab-session/start/{labId}  │               │
     ├─────────────>├─────────────>│              │               │
     │              │              │              │               │
  2. │              │              │ Create Session (DB)          │
     │              │              │              │               │
  3. │              │              │ Send Kafka   │               │
     │              │              ├─────────────>│               │
     │              │              │              │               │
  4. │              │              │ Return 200   │               │
     │<─────────────┼──────────────┤              │               │
     │  { sessionId, vmName, wsUrl }              │               │
     │              │              │              │               │
  5. │ Connect WebSocket                          │               │
     │  ws://gateway/ws/pod-logs?podName=...&token=...          │
     ├─────────────>├──────────────────────────────────────────>│
     │              │              │              │               │
  6. │              │              │              │ Consume Kafka │
     │              │              │              │<──────────────┤
     │              │              │              │               │
  7. │              │              │              │ Create VM     │
     │              │              │              │ (Kubernetes)  │
     │              │              │              │               │
  8. │◄═════════════╪══════════════╪══════════════╪═══════════════┤
     │  WS: { type: "info", message: "Creating VM..." }         │
     │              │              │              │               │
  9. │◄═════════════╪══════════════╪══════════════╪═══════════════┤
     │  WS: { type: "progress", data: { percentage: 50 } }      │
     │              │              │              │               │
 10. │◄═════════════╪══════════════╪══════════════╪═══════════════┤
     │  WS: { type: "terminal_ready", data: { labSessionId } }  │
     │              │              │              │               │
 11. │ User types command in terminal              │               │
     ├──────────────────────────────────────────────────────────>│
     │  WS: "ls -la"│              │              │               │
     │              │              │              │               │
 12. │              │              │              │ SSH → VM      │
     │              │              │              │               │
 13. │◄═════════════╪══════════════╪══════════════╪═══════════════┤
     │  WS: "total 48\ndrwxr-xr-x..."              │               │
     │              │              │              │               │
```

### **Flow 2: Admin Tests Lab**

```
┌─────────┐    ┌─────────┐    ┌─────────┐    ┌──────────┐    ┌────────┐
│Frontend │    │ Gateway │    │CMS BE   │    │  Kafka   │    │Infra Svc│
└────┬────┘    └────┬────┘    └────┬────┘    └────┬─────┘    └────┬───┘
     │              │              │              │               │
  1. │ POST /api/lab/{id}/test    │              │               │
     ├─────────────>├─────────────>│              │               │
     │              │              │              │               │
  2. │              │              │ Send Kafka   │               │
     │              │              ├─────────────>│               │
     │              │              │              │               │
  3. │              │              │ Return 200   │               │
     │<─────────────┼──────────────┤              │               │
     │  { testId, wsUrl }          │              │               │
     │              │              │              │               │
  4. │ Connect WebSocket           │              │               │
     │  ws://gateway/ws/pod-logs?podName=test-vm-...&token=...  │
     ├─────────────>├──────────────────────────────────────────>│
     │              │              │              │               │
  5. │              │              │              │ Consume       │
     │              │              │              │<──────────────┤
     │              │              │              │               │
  6. │              │              │              │ Create Test VM│
     │              │              │              │               │
  7. │◄═════════════╪══════════════╪══════════════╪═══════════════┤
     │  WS: "Creating test VM..."  │              │               │
     │              │              │              │               │
  8. │◄═════════════╪══════════════╪══════════════╪═══════════════┤
     │  WS: "Executing setup step 1/3: apt update"               │
     │              │              │              │               │
  9. │◄═════════════╪══════════════╪══════════════╪═══════════════┤
     │  WS: "✅ Setup step 1/3 passed (exit code: 0)"            │
     │              │              │              │               │
 10. │◄═════════════╪══════════════╪══════════════╪═══════════════┤
     │  WS: "✅ All setup steps completed!"        │               │
     │              │              │              │               │
 11. │◄═════════════╪══════════════╪══════════════╪═══════════════┤
     │  WS: { type: "success", message: "Lab test passed!" }    │
     │              │              │              │               │
```

---

## 📊 Performance Optimizations

### **1. SSH Connection Pre-establishment**

**Problem**: 7-8 connection retries when opening terminal (poor UX)

**Solution**: Pre-connect SSH during VM creation, cache session

```java
// During VM creation (VMUserSessionService)
preConnectAndCacheSSH(vmName, namespace, podName, labSessionId)
  ↓
1. Kubernetes port-forward established
2. JSch session created & connected
3. Store in SshSessionCache (key: "lab-session-{id}")
  ↓
Later, when terminal needed:
  ↓
1. Retrieve from cache (instant!)
2. Open shell channel
3. Ready to use (0 retries)
```

**Result**: Terminal opens instantly, no connection delays

### **2. WebSocket Connection Latch**

**Problem**: Backend might send messages before frontend connects

**Solution**: Backend waits for WebSocket connection before creating VM

```java
// VMUserSessionService & VMTestService
boolean wsConnected = webSocketHandler.waitForConnection(vmName, 30);

if (!wsConnected) {
  log.warn("WebSocket timeout, proceeding anyway (graceful degradation)");
}
```

**Result**: All creation progress messages delivered to frontend

### **3. Persistent Terminal Sessions**

**Problem**: WebSocket disconnect kills entire SSH session

**Solution**: Separate WebSocket session (ephemeral) from Terminal session (persistent)

```java
Map<podName, WebSocketSession> podSessions;      // Ephemeral
Map<podName, TerminalSessionData> terminalSessions; // Persistent

// WebSocket disconnect:
- Remove from podSessions ✅
- Keep terminalSessions ✅ (reconnect possible)

// Lab end (Kafka event):
- Cleanup terminalSessions ✅
- Close SSH connection ✅
```

**Result**: User can reconnect WebSocket without losing terminal state

---

## 🚀 Deployment Architecture

### **Development Environment**

```
Local Machine:
├─ Frontend (Vite dev server: 5173)
├─ Gateway (Spring Boot: 8082)
├─ CMS Backend (Spring Boot: 8080)
├─ Infrastructure Service (Spring Boot: 8081)
├─ PostgreSQL (Docker: 5432)
├─ Kafka (Docker: 9092)
└─ Kubernetes Cluster (KubeVirt)
    └─ VMs created in 'default' namespace
```

### **Production Considerations**

1. **Gateway**: Deploy as standalone service (Docker/Kubernetes)
2. **CMS Backend**: Scalable (stateless, can run multiple instances)
3. **Infrastructure Service**:
   - Stateful (SSH connections)
   - Scale carefully (consider sticky sessions for WebSocket)
4. **Database**: PostgreSQL cluster with replication
5. **Kafka**: Multi-broker cluster for HA
6. **Kubernetes**: Dedicated cluster for student VMs

---

## 🔧 Configuration Files

### **Application Ports**

```
Frontend:      5173 (dev), 3000 (prod)
Gateway:       8082
CMS Backend:   8080
Infra Service: 8081
PostgreSQL:    5432
Kafka:         9092
```

### **Key Environment Variables**

```properties
# Gateway (application.yml)
server.port=8082
app.jwtSecret=labplatformSecretKey...

# CMS Backend (application.properties)
server.port=8080
spring.datasource.url=jdbc:postgresql://localhost:5432/labdb
spring.kafka.bootstrap-servers=localhost:9092

# Infrastructure Service (application.properties)
server.port=8081
kubernetes.config.file.path=/path/to/kubeconfig
kubernetes.namespace=default
ssh.default.username=ubuntu
ssh.default.password=ubuntu

# Frontend (.env)
VITE_API_URL=http://localhost:8082
```

---

## 📝 API Endpoints Summary

### **Authentication** (`/api/auth`)

- `POST /login` - User login
- `POST /refreshtoken` - Refresh JWT

### **Labs** (`/api/lab`)

- `GET /` - List labs (paginated)
- `GET /{id}` - Get lab details
- `POST /` - Create lab (Admin/Instructor)
- `PUT /{id}` - Update lab
- `DELETE /{id}` - Delete lab
- `POST /{id}/test` - Test lab (Admin)
- `GET /{id}/setup-steps` - Get setup steps
- `POST /{id}/setup-steps` - Add setup step

### **User Lab Sessions** (`/api/user-lab-session`)

- `POST /start/{labId}` - Start lab session
- `GET /{sessionId}` - Get session details
- `POST /{sessionId}/submit` - Submit answers
- `GET /user/{userId}` - User's sessions

### **Leaderboard** (`/api/leaderboard`)

- `GET /` - Get leaderboard (paginated)
- `GET /user/{userId}` - User ranking

### **WebSocket Endpoints**

- `ws://gateway:8082/ws/pod-logs?podName={vmName}&token={jwt}`
- `ws://gateway:8082/ws/terminal/{labSessionId}?token={jwt}` (deprecated)

---

## 🐛 Troubleshooting

### **Common Issues**

**1. WebSocket connection fails**

- Check token validity (JWT not expired)
- Verify Gateway is running (port 8082)
- Check CORS settings in Gateway

**2. VM creation timeout**

- Check Kubernetes cluster health
- Verify backing image exists in Longhorn
- Check resource quotas (CPU, memory, storage)

**3. Terminal not connecting**

- Verify SSH is pre-established (check logs)
- Check port-forward to VM pod
- Verify default SSH credentials (ubuntu:ubuntu)

**4. Setup steps fail**

- Check step commands are valid
- Verify timeout settings (default: 300s)
- Check expected exit codes

---

## 📚 References

- **Spring Cloud Gateway**: https://spring.io/projects/spring-cloud-gateway
- **KubeVirt**: https://kubevirt.io/
- **Longhorn**: https://longhorn.io/
- **Apache Kafka**: https://kafka.apache.org/
- **xterm.js**: https://xtermjs.org/

---

## 👥 Team & Contact

**Project**: Lab Platform for Programming Education
**Technology**: Java Spring Boot + React TypeScript
**Infrastructure**: Kubernetes + KubeVirt

---

_Last Updated: December 2024_
