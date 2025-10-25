# Postman Request Collection - BudgetHunter API

**Note:** This API follows RESTful conventions with resource-based URLs and proper HTTP methods (GET, POST, PUT, DELETE).

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

**Expected Response (201 Created):**
```json
{
  "email": "test@example.com",
  "name": "Test User"
}
```

**Note:** After signing up, use the sign_in endpoint to get your auth and refresh tokens.

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
  "authToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "email": "test@example.com",
  "name": "Test User"
}
```

Copy the `authToken` value for authenticated requests and save the `refreshToken` for token refresh.

---

## 3. Refresh Token

**Method:** POST
**URL:** `http://localhost:8080/api/users/refresh_token`
**Headers:**
- Content-Type: application/json

**Body (raw JSON):**
```json
{
  "refreshToken": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

**Expected Response (200 OK):**
```json
{
  "authToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "new-uuid-generated-here",
  "email": "test@example.com",
  "name": "Test User"
}
```

**Note:** This endpoint uses refresh token rotation for enhanced security. Each time you refresh, you receive a new refresh token and the old one is invalidated.

---

## 4. Create Budget

**Method:** POST
**URL:** `http://localhost:8080/api/budgets`
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

## 5. Get Budgets

**Method:** GET
**URL:** `http://localhost:8080/api/budgets`
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

## 6. Add Collaborator

**Method:** POST
**URL:** `http://localhost:8080/api/budgets/1/collaborators`
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

## 7. Get Collaborators

**Method:** GET
**URL:** `http://localhost:8080/api/budgets/1/collaborators`
**Headers:**
- Authorization: Bearer YOUR_JWT_TOKEN_HERE

**Body:** None

**Expected Response (200 OK):**
```json
[
  {
    "email": "user1@example.com",
    "name": "User One"
  },
  {
    "email": "user2@example.com",
    "name": "User Two"
  }
]
```

---

## 8. Get Budget Entries

**Method:** GET
**URL:** `http://localhost:8080/api/budgets/1/entries`
**Headers:**
- Authorization: Bearer YOUR_JWT_TOKEN_HERE

**Body:** None

**Expected Response (200 OK):**
```json
[
  {
    "id": 1,
    "budgetId": 1,
    "amount": 150.00,
    "description": "Grocery shopping",
    "category": "Food",
    "type": "OUTCOME",
    "createdByEmail": "test@example.com",
    "updatedByEmail": "test@example.com",
    "creationDate": "2025-10-02T23:45:00",
    "modificationDate": "2025-10-02T23:50:00"
  },
  {
    "id": 2,
    "budgetId": 1,
    "amount": 2500.00,
    "description": "Monthly salary",
    "category": "Income",
    "type": "INCOME",
    "createdByEmail": "test@example.com",
    "updatedByEmail": null,
    "creationDate": "2025-10-01T09:00:00",
    "modificationDate": "2025-10-01T09:00:00"
  }
]
```

---

## 9. Create Budget Entry

**Method:** POST
**URL:** `http://localhost:8080/api/budgets/1/entries`
**Headers:**
- Content-Type: application/json
- Authorization: Bearer YOUR_JWT_TOKEN_HERE

**Body (raw JSON):**
```json
{
  "amount": 150.00,
  "description": "Grocery shopping",
  "category": "Food",
  "type": "OUTCOME"
}
```

**Expected Response (201 Created):**
```json
{
  "id": 1,
  "budgetId": 1,
  "amount": 150.00,
  "description": "Grocery shopping",
  "category": "Food",
  "type": "OUTCOME",
  "createdByEmail": "test@example.com",
  "updatedByEmail": null,
  "creationDate": "2025-10-02T23:45:00",
  "modificationDate": "2025-10-02T23:45:00"
}
```

---

## 10. Update Budget Entry

**Method:** PUT
**URL:** `http://localhost:8080/api/budgets/1/entries/1`
**Headers:**
- Content-Type: application/json
- Authorization: Bearer YOUR_JWT_TOKEN_HERE

**Body (raw JSON):**
```json
{
  "amount": 175.50,
  "description": "Grocery shopping - updated",
  "category": "Food",
  "type": "OUTCOME"
}
```

**Expected Response (200 OK):**
```json
{
  "id": 1,
  "budgetId": 1,
  "amount": 175.50,
  "description": "Grocery shopping - updated",
  "category": "Food",
  "type": "OUTCOME",
  "createdByEmail": "test@example.com",
  "updatedByEmail": "test@example.com",
  "creationDate": "2025-10-02T23:45:00",
  "modificationDate": "2025-10-02T23:50:00"
}
```

---

## 11. Subscribe to Budget Entry Events (SSE)

**Method:** GET
**URL:** `http://localhost:8080/api/budgets/1/entries/stream`
**Headers:**
- Authorization: Bearer YOUR_JWT_TOKEN_HERE

**Body:** None

**Response:**
- Content-Type: text/event-stream
- Streaming connection that sends events when budget entries are created/updated

**Event Format:**
```json
event: budget-entry
data: {
  "budgetEntry": {
    "id": 123,
    "budgetId": 1,
    "amount": 150.00,
    "description": "Grocery shopping",
    "category": "Food",
    "type": "OUTCOME",
    "creationDate": "2025-10-02T10:30:00",
    "modificationDate": "2025-10-02T10:30:00"
  },
  "userInfo": {
    "email": "user@example.com",
    "name": "John Doe"
  }
}
```

**Note:** For testing in a browser, use the included `test-sse.html` file. Standard Postman doesn't support SSE well. The curl command will keep the connection open and display events as they arrive.

---

## 12. Delete Budget Entry

**Method:** DELETE
**URL:** `http://localhost:8080/api/budgets/1/entries/1`
**Headers:**
- Authorization: Bearer YOUR_JWT_TOKEN_HERE

**Body:** None

**Expected Response (204 No Content):** Empty response with 204 status code

**Note:** Deletes a specific budget entry. The entry must belong to the specified budget, and the authenticated user must have access to the budget.

---

## 13. Remove Collaborator

**Method:** DELETE
**URL:** `http://localhost:8080/api/budgets/1/collaborators/collaborator@example.com`
**Headers:**
- Authorization: Bearer YOUR_JWT_TOKEN_HERE

**Body:** None

**Expected Response (204 No Content):** Empty response with 204 status code

**Note:** Removes a collaborator from a budget. The authenticated user must have access to the budget. Cannot remove the last collaborator from a budget.

---

## 14. Delete Budget

**Method:** DELETE
**URL:** `http://localhost:8080/api/budgets/1`
**Headers:**
- Authorization: Bearer YOUR_JWT_TOKEN_HERE

**Body:** None

**Expected Response (204 No Content):** Empty response with 204 status code

**Note:** Deletes a budget and all associated entries and collaborator relationships. The authenticated user must have access to the budget. This operation cannot be undone.

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

### Refresh Token
```bash
curl -X POST http://localhost:8080/api/users/refresh_token \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"a1b2c3d4-e5f6-7890-abcd-ef1234567890"}'
```

### Create Budget
```bash
curl -X POST http://localhost:8080/api/budgets \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{"name":"Monthly Budget","amount":5000.00}'
```

### Get Budgets
```bash
curl -X GET http://localhost:8080/api/budgets \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

### Add Collaborator
```bash
curl -X POST http://localhost:8080/api/budgets/1/collaborators \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{"budgetId":1,"email":"collaborator@example.com"}'
```

### Get Collaborators
```bash
curl -X GET http://localhost:8080/api/budgets/1/collaborators \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

### Get Budget Entries
```bash
curl -X GET http://localhost:8080/api/budgets/1/entries \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

### Create Budget Entry
```bash
curl -X POST http://localhost:8080/api/budgets/1/entries \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "amount": 150.00,
    "description": "Grocery shopping",
    "category": "Food",
    "type": "OUTCOME"
  }'
```

### Update Budget Entry
```bash
curl -X PUT http://localhost:8080/api/budgets/1/entries/1 \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "amount": 175.50,
    "description": "Grocery shopping - updated",
    "category": "Food",
    "type": "OUTCOME"
  }'
```

### Subscribe to Budget Entry Events (SSE)
```bash
curl -N -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  "http://localhost:8080/api/budgets/1/entries/stream"
```

### Delete Budget Entry
```bash
curl -X DELETE http://localhost:8080/api/budgets/1/entries/1 \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

### Delete Collaborator
```bash
curl -X DELETE http://localhost:8080/api/budgets/1/collaborators/collaborator@example.com \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

### Delete Budget
```bash
curl -X DELETE http://localhost:8080/api/budgets/1 \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```
