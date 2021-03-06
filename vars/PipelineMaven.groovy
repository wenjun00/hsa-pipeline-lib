// 入口方法
def call(Map userConfig) {

    // 为了使用方面，脚本更加健壮，这儿统一设置默认值
    def defaultConfig = [
        // 应用仓库地址
        "repo": "",
        // 制品路径，可以有通配符，比如 hsa-main-generic/target/*.jar
        "artifact": "",
        // pom路径
        "pom": "pom.xml",
        // 要部署的服务器列表
        "servers": [],
        // 通用的jvm参数，会加到每个服务器的jvm启动参数中
        "commonJvmArgs": "-XX:InitialHeapSize=128m -XX:MaxHeapSize=1024m -Dlogging.file.path=/home/\$USER/logs",
        // mvn命令地址
        "mvn": "/usr/local/maven/bin/mvn",
        // git账号Id
        "credId": "",
        // 检测应用正常启动的超时时间
        "verifyAppStartupTimeout": 60,
        // 钉钉机器人的token
        "dingRobotAccessToken": "27b9b065003710b38dc51b83638f1ac490a7e21d69a6e8a7fe655429d4d26b95",
        // 配置点击消息跳转路径
        "jobUrl" : "http://jks.hxmec.com/"
    ]

    // 用户的输入覆盖默认配置
    def config = defaultConfig + userConfig

    // 选择的服务器
    def selectedServer = null
    def isDeploy = true
    def isRestart = false
    def isPackage = false
    def message = ''
    def workspacepath= '/Users/wenjun/.jenkins/workspace'
    def severChoice = config.servers.keySet().join("\n")
    pipeline {
        agent any
        options { timestamps () }
        parameters {
            choice(name: 'scene', choices: "deploy:部署\npackage:打包\nrestart:重启", description: '场景\n部署:需要选择分支和服务器\n重启：选择服务器即可，一般用于配置修改后重启的场景')
            // 要指定useRepository
            gitParameter branchFilter: 'origin/(.*)', defaultValue: '', name: 'branchOption', type: 'PT_BRANCH', 
                        quickFilterEnabled: true, useRepository:"${config.repo}", listSize: "20", description: "选择要部署的分支"
            string(name: 'inputBranch', defaultValue: 'test', description: '代码分支（如果没有在上面选择分支，则以此处填写的分支进行构建）')
            choice(name: 'server', choices: "${severChoice}", description: '服务器列表')
            booleanParam(name: 'forceUpdateSnapshot', defaultValue: false, description: '强制刷新snapshot依赖')
        }
        stages {
            stage("解析参数") {
                steps {
                    script {
                        selectedServer = config.servers[params.server]
                        selectedServer['jobUrl'] = config['jobUrl']
                        println("选择的测试服务器信息：${selectedServer}")
                        isDeploy = params.scene.split(":")[0] == "deploy"
                        isPackage = params.scene.split(":")[0] == "package"
                        isRestart = params.scene.split(":")[0] == "restart"
                        println("部署模式为：${params.scene}")
                    }
                }
            }
            stage('拉取代码') {
                when {
                    expression {
                        return isDeploy || isPackage
                    }
                }
                steps {
                    script {
                        echo ">>>>>>>>>>>>>>>>>>>>开始拉取代码"
                        def branch = ""
                        // 以选择的分支为部署分支
                        if (params.branchOption) {
                            branch = params.branchOption
                        } else {
                            branch = params.inputBranch
                        }
                        echo "分支：" + branch
                        git credentialsId: config.credId, url: config.repo, branch: branch
                        echo ">>>>>>>>>>>>>>>>>>>>拉取代码成功"
                    }
                }
            }
             stage("编译打包") {
                when {
                    expression {
                        return isDeploy || isPackage
                    }
                }
                steps {
                    script {
                        sh "tree -L 2"
                        echo ">>>>>>>>>>>>>>>>>>>>开始编译打包"
                        def updateSnapshot = params.forceUpdateSnapshot ? "-U" : ""
                        sh "${config.mvn} -f ${config.pom} clean package ${updateSnapshot} -Dmaven.test.skip=true -Dmaven.javadoc.skip=true"
                        echo ">>>>>>>>>>>>>>>>>>>>编译打包成功"
                    }
                }
            }
            stage("拷贝jar/war包") {
                when {
                    expression {
                        return isDeploy
                    }
                }
                steps {
                    script {
                        echo ">>>>>>>>>>>>>>>>>>>>开始拷贝jar/war包"
                        sh "sshpass -p ${selectedServer.passwd} scp -P ${selectedServer.sshPort} ${workspacepath}/${config.artifact} ${selectedServer.user}@${selectedServer.ip}:~/app/app.jar"
                        echo ">>>>>>>>>>>>>>>>>>>>拷贝成功"
                    }
                }
            }
            
            stage("启动应用") {
                when {
                    expression {
                        return isDeploy || isRestart
                    }
                }
                steps {
                    script {
                        echo ">>>>>>>>>>>>>>>>>>>>开始启动应用"
                        // 端口号+通用jvm参数
                        def jvmArgs = "-Dserver.port=${selectedServer.port} " + config.commonJvmArgs
                        // 服务器特有jvm参数
                        if (selectedServer.jvmArgs) {
                            jvmArgs += " " + selectedServer.jvmArgs
                        }
                        println("jvm参数：${jvmArgs}")
                        sh "sshpass -p ${selectedServer.passwd} ssh -p ${selectedServer.sshPort} -o StrictHostKeychecking=no ${selectedServer.user}@${selectedServer.ip} ' sh ~/bin/scripts/restartJar.sh \"${jvmArgs}\" '"
                    }

                    script {
                        // 休眠20秒，等待应用关闭
                        sleep 20
                        try {
                            timeout(time: config.verifyAppStartupTimeout, unit: 'SECONDS') {
                                while(true) {
                                    try {
                                        // 使用nc命令探测端口是否开启
                                        sh "nc -z ${selectedServer.ip} ${selectedServer.port}"
                                        return true
                                    } catch (exception) {
                                        // 隔10秒后再次重试
                                        sleep 10
                                    }
                                }
                            }
                                           
                            message = '部署成功'
                            echo ">>>>>>>>>>>>>>>>>>>>端口检测成功，应用启动完成"

                            println("服务器信息：${selectedServer}")
                        } catch(exception) {
                            message = '部署失败'

                            error ">>>>>>>>>>>>>>>>>>>>${selectedServer.ip} ${selectedServer.port} 未监测到应用端口启动，请到如下服务器查看应用log：${selectedServer}"
                        }
                    }
                }
            }
        }
    }
}
