def call(Map configMap) {
    pipeline {
        agent {
            node {
                label 'ROBOSHOP'
            }
        }

        environment {
            appVersion = ""
            acc_id = "978092319764"
            project = configMap.get("project")
            component = configMap.get("component")
            org = "Pandu-sys"
        }

        options {
            disableConcurrentBuilds()
            timeout(time: 15, unit: 'MINUTES')
        }
        /* parameters {
            string(name: 'PERSON', defaultValue: 'Mr Jenkins', description: 'Who should I say hello to?')
            text(name: 'BIOGRAPHY', defaultValue: '', description: 'Enter some information about the person')
            booleanParam(name: 'DEPLOY', defaultValue: true, description: 'Toggle this value')
            choice(name: 'CHOICE', choices: ['One', 'Two', 'Three'], description: 'Pick something')
            password(name: 'PASSWORD', defaultValue: 'SECRET', description: 'Enter a password')
        } */
        // Build
        stages {
            stage ('Read version'){
                steps{
                    script{
                        def packageJson = readJSON file: 'package.json'
                        // Extract the version field
                        appVersion = packageJson.version 
                        // Output to console log
                        echo "The application version is: ${appVersion}"
                    }
                }
            }
            stage('Deploy') {
                steps {
                    script {
                        try {
                           withAWS(credentials: 'aws-creds', region: 'us-east-1'){
                                sh """
                                aws eks update-kubeconfig --region us-east-1 --name roboshop-dev
                                cd helm
                                helm upgrade --install ${component} . -f values-dev.yaml -n roboshop-dev\
                                --set deployment.imageVersion=${appVersion}\
                                --wait --timeout 5m

                                kubectl rollout status deployment/${component} -n roboshop-dev --timeout=2m
                                """
                           }
                           utils.updateCommitStatus("success", "dev deploy is successful", "dev deploy")
                        }
                        catch(Exception e){
                              utils.updateCommitStatus("failure", "dev deploy is failed", "dev deploy")
                              throw e
                        }
                    }
                }
            }
            stage('api-tests'){
                steps{
                    script{
                        try{
                            build job: 'catalogue-api-tests',
                            wait: true, // should wait
                            propagater: true, // downstream errors are considered as upstream errors too
                            parameters: [
                                string(name: 'NAMESPACE', value: 'roboshop-dev'),
                                string(name: 'COMMIT_ID', value: "${env.GIT_COMMIT}")
                            ]
                            utils.updateCommitStatus("success", "api tests is successful", "api tests")
                        }
                        catch(Exception e){
                              utils.updateCommitStatus("failure", "api tests is failed", "api tests")
                              throw e
                        }
                    }
                }
            }
        }

        post { 
            always { 
                echo 'I will always say Hello again!'
            }
            success {
            slackSend channel: '#jenkins-alerts-90s',
                color: 'good',
                message: "Success: Job '${env.JOB_NAME}' (${env.BUILD_NUMBER}) (${env.BUILD_URL}) ran successfully."
            }
            failure {
            slackSend channel: '#jenkins-alerts-90s',
                    color: 'danger',
                    message: "Failed: Job '${env.JOB_NAME}' (${env.BUILD_NUMBER}) (${env.BUILD_URL}) has failed."
           }
        }
    }
}