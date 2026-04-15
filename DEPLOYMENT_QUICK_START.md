# 🚀 Deployment Quick Start

## Configuración Inicial (Una Sola Vez)

Crea el archivo de configuración del servidor:

```bash
cp .env.server.example .env.server
# Edita .env.server con tu IP real
```

**Contenido de .env.server:**
```bash
SERVER_IP=YOUR_DROPLET_IP
SERVER_USER=root
```

⚠️ **IMPORTANTE**: `.env.server` está en `.gitignore` y NO se debe commitear.

## Para Actualizar la Aplicación en Producción

```bash
./deploy.sh
```

Eso es todo! El script:
1. Compila localmente (2-3 minutos)
2. Sube el JAR al servidor
3. Reinicia los contenedores
4. Verifica que todo esté funcionando

También puedes especificar la IP manualmente:
```bash
./deploy.sh YOUR_SERVER_IP
```

## Comandos Útiles

### Ver el Estado del Servidor

```bash
# SSH al servidor
ssh root@YOUR_SERVER_IP

# Ver contenedores corriendo
docker compose ps

# Ver logs en tiempo real
docker compose logs -f backend

# Ver logs de PostgreSQL
docker compose logs -f postgres
```

### Reiniciar la Aplicación

```bash
ssh root@YOUR_SERVER_IP "cd /opt/budgethunter && docker compose restart"
```

### Detener la Aplicación

```bash
ssh root@YOUR_SERVER_IP "cd /opt/budgethunter && docker compose down"
```

### Iniciar la Aplicación

```bash
ssh root@YOUR_SERVER_IP "cd /opt/budgethunter && docker compose up -d"
```

### Ver Uso de Recursos

```bash
ssh root@YOUR_SERVER_IP "docker stats --no-stream"
```

## URLs de Producción

- **API Base**: http://YOUR_SERVER_IP:8080
- **Health Check**: http://YOUR_SERVER_IP:8080/actuator/health
- **Swagger UI**: http://YOUR_SERVER_IP:8080/swagger-ui/index.html

## Estructura de Archivos

```
BudgetHunterBackend/
├── deploy.sh              # Script de deployment (USA ESTE)
├── docker-compose.yml     # Configuración de producción
├── .env                   # Variables de entorno (NO COMMITEAR)
└── database/
    └── schema.sql         # Schema de base de datos
```

## Troubleshooting

### La aplicación no responde

```bash
# 1. Ver logs
ssh root@YOUR_SERVER_IP "cd /opt/budgethunter && docker compose logs backend --tail=100"

# 2. Verificar que los contenedores estén corriendo
ssh root@YOUR_SERVER_IP "cd /opt/budgethunter && docker compose ps"

# 3. Reiniciar
ssh root@YOUR_SERVER_IP "cd /opt/budgethunter && docker compose restart"
```

### Error de conexión a base de datos

```bash
# Verificar que PostgreSQL esté healthy
ssh root@YOUR_SERVER_IP "cd /opt/budgethunter && docker compose ps postgres"

# Reiniciar solo PostgreSQL
ssh root@YOUR_SERVER_IP "cd /opt/budgethunter && docker compose restart postgres"
```

### Disco lleno

```bash
# Ver uso de disco
ssh root@YOUR_SERVER_IP "df -h"

# Limpiar imágenes y contenedores viejos
ssh root@YOUR_SERVER_IP "docker system prune -f"

# Limpiar logs
ssh root@YOUR_SERVER_IP "cd /opt/budgethunter && truncate -s 0 logs/*.log"
```

### Memoria agotada

```bash
# Ver uso de memoria
ssh root@YOUR_SERVER_IP "free -h"

# Si está al 100%, considera upgradearlo droplet a 2GB RAM
```

## Costos

- **Droplet 1GB RAM**: $12/mes
- **Droplet 2GB RAM**: $18/mes (recomendado para producción)
- **Backups**: +20% del costo del droplet

## Próximos Pasos

1. ✅ Configurar monitoreo con UptimeRobot (ver MONITORING.md)
2. ⏳ Configurar dominio personalizado (opcional)
3. ⏳ Configurar SSL/HTTPS (opcional, cuando tengas dominio)
4. ⏳ Configurar backups automáticos

---

**Última actualización**: 2026-04-14
