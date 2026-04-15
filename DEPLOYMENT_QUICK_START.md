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

### HTTPS (Recomendado - Con SSL)
- **API Base**: https://budgethunter.duckdns.org
- **Health Check**: https://budgethunter.duckdns.org/actuator/health
- **Swagger UI**: https://budgethunter.duckdns.org/swagger-ui/index.html

### HTTP (Legacy - Redirige a HTTPS)
- **API Base**: http://budgethunter.duckdns.org (redirige automáticamente a HTTPS)

**Nota**: El tráfico HTTP se redirige automáticamente a HTTPS. Usa siempre HTTPS en tu app móvil.

## Configuración SSL/HTTPS

### Estado Actual
✅ SSL configurado con Let's Encrypt
✅ Dominio: `budgethunter.duckdns.org`
✅ Certificado válido hasta Julio 2026
✅ Renovación automática cada 90 días

### Comandos SSL

```bash
# Ver estado del certificado
ssh root@YOUR_SERVER_IP "certbot certificates"

# Test de renovación (no renueva realmente)
ssh root@YOUR_SERVER_IP "certbot renew --dry-run"

# Renovar manualmente (si es necesario)
ssh root@YOUR_SERVER_IP "certbot renew"

# Ver logs de Nginx
ssh root@YOUR_SERVER_IP "tail -f /var/log/nginx/access.log"

# Reiniciar Nginx
ssh root@YOUR_SERVER_IP "systemctl restart nginx"
```

### Reconfigurar SSL (si cambias de dominio)

```bash
./setup-ssl.sh NEW_DOMAIN.duckdns.org your@email.com
```

## Estructura de Archivos

```
BudgetHunterBackend/
├── deploy.sh              # Script de deployment (USA ESTE)
├── setup-ssl.sh           # Script de configuración SSL
├── docker-compose.yml     # Configuración de producción
├── .env                   # Variables de entorno (NO COMMITEAR)
├── .env.server            # IP del servidor (NO COMMITEAR)
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

1. ✅ SSL/HTTPS configurado con Let's Encrypt
2. ⏳ Configurar monitoreo con UptimeRobot (ver MONITORING.md)
3. ⏳ Configurar backups automáticos
4. ⏳ Actualizar app móvil con URL HTTPS

## Actualizar App Móvil

Para usar HTTPS en tu app Android, actualiza la URL base:

### Antes (HTTP sin SSL):
```kotlin
// En tu archivo de configuración (ej: Constants.kt o NetworkModule.kt)
const val BASE_URL = "http://YOUR_SERVER_IP:8080"
```

### Ahora (HTTPS con SSL):
```kotlin
const val BASE_URL = "https://budgethunter.duckdns.org"
```

**Nota**: No necesitas el puerto `:8080` porque HTTPS usa el puerto 443 por defecto.

### Verificar en tu app:
1. Actualiza la constante `BASE_URL`
2. Recompila la app
3. Prueba login/sign up
4. Todas las requests ahora irán por HTTPS 🔒

---

**Última actualización**: 2026-04-15
