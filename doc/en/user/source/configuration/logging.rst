.. _logging:

Advanced log configuration
==========================

GeoServer uses the Log4J framework for logging, which is configured by selecting a logging profile (in the :ref:`global settings <config_globalsettings_log_location>`).

In addition to the built-in profiles you may setup a custom logging profile, or override the logging configuration completely (even to use another another logging library altogether).

Custom logging profiles
-----------------------

Anyone can write a new logging profile by adding a Log4J configuration file to the list of files already available in the ``$GEOSERVER_DATA_DIR/logs`` folder.

Profiles in this folder that match :file:`*_LOGGING.*` will be listed on the global settings page as available for use. The name of the file, excluding the extension, will be presented as the profile name.

Here is an example, taken from the :download:`DEFAULT_LOGGING.xml </../../../../src/main/src/main/resources/DEFAULT_LOGGING.xml>`  configuration, which enables additional GeoServer log messages to be included in the logs:

.. literalinclude:: /../../../../src/main/src/main/resources/DEFAULT_LOGGING.xml
   :language: xml


Any custom configuration can be setup to enable specific packages to emit logs at the desired logging level.

There are however a few rules to follow:

* Custom levels are are available for ``CONFIG`` and ``FINEST`` levels.
  
* Appenders are used to output logging information, with GeoServer providing external configuration for appenders named ``geoserverlogfile`` and ``stdout``.

  * Always include a ``geoserverlogfile`` ``FileAppender`` or ``RollingFile`` appender that GeoServer will configure to work against the location configured in the :ref:`global settings <config_globalsettings_log_location>`.

    Care is taken to preserve your file extension when updating ``<filename>`` location, so if you wish to log to :file:`access_logs.txt` you may do so, and the :file:`txt` extension will be preserved.

  * When setting ``geoserverlogfile`` appender as ``RollingFile`` appender, care is taken to preserve your ``<filePattern>`` extensions, which must align with the roll over strategies configured.
  
    As an example ``-%i`` is used with DefaultRolloverStrategy to produce a maximum of ``3`` backup files.

    .. literalinclude:: /../../../../src/main/src/main/resources/DEFAULT_LOGGING.xml
       :language: xml
       :start-at: <RollingFile
       :end-at: </RollingFile>
       :dedent: 8

  * a ``Console`` appender writing to the standard output should be called ``stdout`` and again GeoServer will enable/disable it according to the configuration set in the :ref:`global settings <config_globalsettings_log_stdout>`
  
    .. literalinclude:: /../../../../src/main/src/main/resources/DEFAULT_LOGGING.xml
       :language: xml
       :start-at: <Console
       :end-at: </Console>
       :dedent: 8
       
* Loggers are used to collect messages from geoserver components, and individual libraries used.
  
  * GeoServer Logger names match up with the package names in the project javadocs overview (available for download).
  
    As an example package ``org.geoserver.wms`` is listed, allowing level of WMS service logging to be controlled:
    
    .. code-block:: xml
    
        <Logger name="org.geoserver.wms" level="debug" additivity="false">
            <AppenderRef ref="stdout"/>
            <AppenderRef ref="geoserverlogfile"/>
        </Logger>
  
  * GeoTools Logger names match up with the package names in the `project javadocs overview <https://docs.geotools.org/latest/javadocs/overview-summary.html>`__.
  
    As an example package ``org.geotools.data.shapefile`` is listed, allowing level of shapefile logging to be controlled:
    
    .. code-block:: xml
    
        <Logger name="org.geotools.data.shapefile" level="debug" additivity="false">
            <AppenderRef ref="stdout"/>
            <AppenderRef ref="geoserverlogfile"/>
        </Logger>

  * Assign a level to each logger indicating the level of detail you wish to record:
  
    ======= ===========
    Level   Description
    ======= ===========
    OFF     Turn off all logging
    FATAL   A serious problem has occured, application may be crashing or in need of restart
    ERROR   Problem has occured, application unable to perform requested operation
    WARN    Potential problem, application will try and continue
    INFO    Normal function indicating what application is doing.
    CONFIG  Normal application function during application startup and configuration
    DEBUG   Internal messages intended for debugging
    TRACE   Metod by method tracing of execution
    FINEST  Really detailed troubleshooting of an algorithm
    ALL     Turn on all logging
    ======= ===========
    
    The more verbose logging levels potentially include a strack-trace showing where the message occurred.

  * Use ``additivity="false"`` to prevent a message collected from one logger from being passed to the next.
  
  If you end up with double log messages chances check for this common misconfiguration.
  
  * The ``Root`` logger is last in the list and should collect everything.


Example of console only logging
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

1. Copy :download:`QUIET_LOGGING.xml </../../../../src/main/src/main/resources/QUIET_LOGGING.xml>`

2. Add appenders for geoserver 

Overriding the log location setup in the GeoServer configuration
----------------------------------------------------------------

When setting up a cluster of GeoServer machines it is common to share a single data directory among all the cluster nodes.

There is however a gotcha, all nodes would end up writing the logs in the same file, which would cause various kinds of troubles depending on the operating system file locking rules (a single server might be able to write, or all togheter in an uncontrolled manner resulting in an unreadable log file).

A common choice could be to use the machine name as a distinction, setting values such as  ``logs/geoserver_node1.log``, ``logs/geoserver_node2.log`` and so on: in this case all the log files would still be contained in the data directory and properly rotated, but each server would have its own separate log file to write on.

In this case it is convenient to set a separate log location for each GeoServer node:

* The ``GEOSERVER_LOG_LOCATION`` parameter can be set as system property, enviroment variables, or servlet context parameters::

      GEOSERVER_LOG_LOCATION=<the location of the file>
  
  This setting overides global setting, and is applied to ``geoserverlogfile`` appender as a template for filename and filePattern.
  
* This same effect may be obtained using Log4J `property substitution <https://logging.apache.org/log4j/2.x/manual/configuration.html#PropertySubstitution>`__, where a wide range of `property lookups <https://logging.apache.org/log4j/2.x/manual/lookups.html>`__ are available.

  .. code-block:: xml

     <RollingFile name="geoserverlogfile">
         <filename>logs/geoserver-${hostName}.log</filename>
         <filePattern>logs/geoserver-${hostName}-%d{yyyy-MM-dd-HH}-%i.zip</filePattern>
         <PatternLayout pattern="%date{dd mmm HH:mm:ss} %-6level [%logger{2}] - %msg%n"/>
         <Policies>
           <OnStartupTriggeringPolicy />
           <SizeBasedTriggeringPolicy size="20 MB" />
           <TimeBasedTriggeringPolicy />
         </Policies>
         <DefaultRolloverStrategy max="9" fileIndex="min"/>
     </RollingFile>
   
  Where ``${hostName}`` is the current system host name or ip address.

Forcing GeoServer to relinquish Log4J control
---------------------------------------------

GeoServer internally overrides the Log4J configuration by using the current logging configuration as a template and appling the log location and standard output settings configured by the administrator.

If you wish GeoServer not to override the normal Log4J behavior you can set the following parameter among the JVM system variables, enviroment variables, or servlet context parameters::

  RELINQUISH_LOG4J_CONTROL=true
  
This can be combined with ``log4j2.configurationFile`` system property to `configure Log4J externally <https://logging.apache.org/log4j/2.x/manual/configuration.html#AutomaticConfiguration>`__ :

  -DRELINQUISH_LOG4J_CONTROL=true -Dlog4j2.configurationFile=logging_configuration.xml
  
Forcing GeoServer to use an alternate logging redirection
---------------------------------------------------------

GeoServer uses the GeoTools logging framework, which in turn is based on Java Logging, but allowing to redirect all message to an alternate framework of users choice.

By default GeoServer setups a Log4J redirection, but it is possible to configure GeoServer to use plain Java Logging, or Commons Logging instead (support for other loggers is also possible by using some extra programming).

If you wish to force GeoServer to use a different logging mechanism set the following parameters among the JVM system variables, enviroment variables, or servlet context parameters::

  GT2_LOGGING_REDIRECTION=[CommonsLogging,JavaLogging,Log4J,Log4J2,LogBack]
  RELINQUISH_LOG4J_CONTROL=true
  
As noted in the example you'll also have to demand that GeoServer does not exert control over the Log4J configuration.

Force java logging example
^^^^^^^^^^^^^^^^^^^^^^^^^^

As an example to configure java logging::

  -DRELINQUISH_LOG4J_CONTROL=true -DGT2_LOGGING_REDIRECTION=JavaLogging -Djava.util.logging.config.file=logging.properties
  
With java util logging configuration provided by :file:`logging.properties`:

.. code-block:: properties

   handlers=java.util.logging.ConsoleHandler

   java.util.logging.ConsoleHandler.level = ALL
   java.util.logging.ConsoleHandler.formatter = org.geotools.util.logging.MonolineFormatter
   org.geotools.util.logging.MonolineFormatter.source = class:short

   .level= ALL
   org.geoserver.level = CONFIG
   org.vfny.geoserver.level = WARN
   
   org.geotools.level = WARN
   org.geotools.factory.level = WARN
