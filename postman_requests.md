# Postman Request Collection - BudgetHunter API

## 1. Sign Up

**Method:** POST
**URL:** `http://localhost:8080/api/users/sign_up`
**Headers:**
- Content-Type: application/json

**Body (raw JSON):**
```json
{
  "email": "test@example.com",
  "name": "Test User",
  "password": "password123"
}
```

---

## 2. Sign In

**Method:** POST
**URL:** `http://localhost:8080/api/users/sign_in`
**Headers:**
- Content-Type: application/json

**Body (raw JSON):**
```json
{
  "email": "test@example.com",
  "password": "password123"
}
```

**Response:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "email": "test@example.com",
  "name": "Test User"
}
```

Copy the `token` value for the next request.

---

## 3. Create Budget

**Method:** POST
**URL:** `http://localhost:8080/api/budgets/create_budget`
**Headers:**
- Content-Type: application/json
- Authorization: Bearer YOUR_JWT_TOKEN_HERE

**Body (raw JSON):**
```json
{
  "name": "Monthly Budget",
  "amount": 5000.00
}
```

**Expected Response (201 Created):**
```json
{
  "id": 1,
  "name": "Monthly Budget",
  "amount": 5000.00
}
```

---

## 4. Get Budgets

**Method:** GET
**URL:** `http://localhost:8080/api/budgets/get_budgets`
**Headers:**
- Authorization: Bearer YOUR_JWT_TOKEN_HERE

**Body:** None

**Expected Response (200 OK):**
```json
[
  {
    "id": 1,
    "name": "Monthly Budget",
    "amount": 5000.00
  },
  {
    "id": 2,
    "name": "Vacation Budget",
    "amount": 2000.00
  }
]
```

---

## 5. Add Collaborator

**Method:** POST
**URL:** `http://localhost:8080/api/budgets/add_collaborator`
**Headers:**
- Content-Type: application/json
- Authorization: Bearer YOUR_JWT_TOKEN_HERE

**Body (raw JSON):**
```json
{
  "budgetId": 1,
  "email": "collaborator@example.com"
}
```

**Expected Response (201 Created):**
```json
{
  "budgetId": 1,
  "budgetName": "Monthly Budget",
  "collaboratorEmail": "collaborator@example.com",
  "collaboratorName": "John Doe"
}
```

---

## cURL Commands (Alternative)

### Sign Up
```bash
curl -X POST http://localhost:8080/api/users/sign_up \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","name":"Test User","password":"password123"}'
```

### Sign In
```bash
curl -X POST http://localhost:8080/api/users/sign_in \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123"}'
```

### Create Budget
```bash
curl -X POST http://localhost:8080/api/budgets/create_budget \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{"name":"Monthly Budget","amount":5000.00}'
```

### Get Budgets
```bash
curl -X GET http://localhost:8080/api/budgets/get_budgets \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

### Add Collaborator
```bash
curl -X POST http://localhost:8080/api/budgets/add_collaborator \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{"budgetId":1,"email":"collaborator@example.com"}'
```
