# FROM openjdk:8-jre-alpine
# Had to use the "full" Linux version of this container since with the alpine version the GCP datastore API wasn't working
FROM openjdk:8

# Create the folder that hold all libraries that the app requires
RUN mkdir /dependency-jars

# Copy the dependency jars into that folder
ADD target/dependency-jars /dependency-jars

# Copy the app
ADD target/HcmGcp.jar /

# When started, the conatiner will start the Java app
CMD ["java", "-jar", "HcmGcp.jar"]

#FROM payara/micro

#COPY target/NgHcmGcpMgr.war $DEPLOY_DIR/gcp.war