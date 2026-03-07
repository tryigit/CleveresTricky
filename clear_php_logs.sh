#!/bin/bash
# Clear PHP error logs daily

LOG_FILE="/var/www/vhosts/tryigit.dev/httpdocs/php_error_log"

if [ -f "$LOG_FILE" ]; then
    > "$LOG_FILE"
    echo "$(date): PHP error logs cleared successfully." >> /var/log/php_log_cleanup.log
else
    echo "$(date): Log file $LOG_FILE not found." >> /var/log/php_log_cleanup.log
fi
