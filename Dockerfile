FROM openjdk:8-jre-alpine

# Create the folder that hold all libraries that the app requires
RUN mkdir /dependency-jars

# Copy the dependency jars into that folder
ADD target/dependency-jars /dependency-jars

# Copy the app
ADD target/NgHcmGcpMgr.jar /

# When started, the conatiner will start the Java app
CMD ["java", "-jar", "NgHcmGcpMgr.jar"]

#FROM payara/micro

#COPY target/NgHcmGcpMgr.war $DEPLOY_DIR/gcp.war