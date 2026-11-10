// Déploiement Paylance (staging) — serveur 180.149.196.68, agent Jenkins local à cette machine.
// Suppose que les variables d'environnement applicatives (PAYLANCE_DB_*, PAYLANCE_ENCRYPTION_MASTER_KEY,
// PAYLANCE_KEYCLOAK_ISSUER_URI...) sont déjà configurées côté serveur (ex: EnvironmentFile= du service systemd),
// ce pipeline ne gère que le build et le déploiement du jar, pas les secrets applicatifs.
pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }

    environment {
        APP_NAME = "paylance-backend"
        JAR_FILE = "target/paylance.jar"
        DEPLOY_PATH = "/opt/paylance/paylance-backend-staging/app.jar"
        SERVICE_NAME = "paylance_staging"
        BACKUP_DIR = "/opt/paylance/paylance-backups/paylance_staging/"
        // Actuator servi sur le port de management (127.0.0.1), voir application-staging.yml
        HEALTH_URL = "http://localhost:8093/actuator/health"
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                sh 'mvn clean package -DskipTests'
                sh 'ls -lah target/'
            }
        }

        stage('Backup current version') {
            steps {
                sh """
                set +e
                mkdir -p ${BACKUP_DIR}

                if [ -f ${DEPLOY_PATH} ]; then
                    cp ${DEPLOY_PATH} ${BACKUP_DIR}/app_\$(date +%s).jar
                    echo "Backup effectué"
                else
                    echo "Aucune version existante à sauvegarder"
                fi
                """
            }
        }

        stage('Deploy') {
            steps {
                sh """
                set -e

                echo "Déploiement du nouveau jar..."
                cp ${JAR_FILE} ${DEPLOY_PATH}

                echo "Restart du service..."
                sudo systemctl restart ${SERVICE_NAME}
                """
            }
        }

        stage('Health Check') {
            steps {
                sh """
                #!/bin/bash

                echo "Checking application health..."

                for i in \$(seq 1 20); do

                    RESPONSE=\$(curl -s -o /dev/null -w "%{http_code}" ${HEALTH_URL} || true)

                    echo "HTTP Response: \$RESPONSE"

                    if [ "\$RESPONSE" = "200" ]; then

                        BODY=\$(curl -s ${HEALTH_URL} || true)

                        echo "\$BODY"

                        echo "\$BODY" | grep "UP"

                        if [ \$? -eq 0 ]; then
                            echo "Application UP"
                            exit 0
                        fi
                    fi

                    echo "Tentative \$i: application pas encore prête..."
                    sleep 10
                done

                echo "Application non démarrée après attente"

                echo "=== Logs service ==="
                sudo journalctl -u ${SERVICE_NAME} -n 100 --no-pager

                exit 1
                """
            }
        }
    }

    post {

        failure {
            echo "Échec détecté → rollback en cours..."

            sh """
            LAST_BACKUP=\$(ls -t ${BACKUP_DIR}/app_*.jar 2>/dev/null | head -n 1)

            if [ -f "\$LAST_BACKUP" ]; then
                echo "Rollback vers \$LAST_BACKUP"
                cp \$LAST_BACKUP ${DEPLOY_PATH}
                sudo systemctl restart ${SERVICE_NAME}
            else
                echo "Aucun backup trouvé"
            fi
            """
        }

        success {
            echo "Déploiement staging réussi"
        }

        always {
            cleanWs()
        }
    }
}
