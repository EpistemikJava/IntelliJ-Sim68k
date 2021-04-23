/* ******************************************************************************
 *
 * LogControl.java
 *
 * Copyright (c) 2008-2021 Mark Sattolo <epistemik@gmail.com>
 *
 * IntelliJ-IDEA version created 2021-02-13
 *
 ********************************************************************************/

import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.logging.*;


/**
 *  Manage logging for the package
 *  @author Mark Sattolo
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class LogControl {
    /*
     *        C O N S T R U C T O R S
     ************************************************************************************************************ */
    /**
     * <b>Usual</b> CONSTRUCTOR <br>
     * Set up my Logger, Handler(s) and Formatter(s) and initiate logging
     * @param lev initial log {@link Level} received from main class
     * @see MhsLogger#getNewLogger(String)
     * @see Logger#addHandler(Handler)
     */
    public LogControl(final String lev) {
        // get a Logger for this package
        myLogger = MhsLogger.getNewLogger( MhsLogger.class.getName() );

        setFileHandlers();
        myLogger.addHandler(xmlHandler);
        myLogger.addHandler(textHandler);

        // set the level of detail that gets logged to files
        fileLevel = DEFAULT_FILE_LEVEL;
        myLogger.setLevel( fileLevel );
        myLogger.severe( "INITIAL file log level = " + fileLevel );

        // need to set up root Logger to display properly to Console
        setRootLogger( Level.parse(lev) );
    }

    /*
     *        M E T H O D S
     *********************************************************************************************************** */
    /**
     * Set up my Handler(s) and Formatter(s) <br>
     * <em>called by</em> {@link LogControl#LogControl(String)}
     * @see FileHandler
     * @see Formatter
     * @see Handler#setFormatter
     */
    private void setFileHandlers() {
        String baseName = LOG_SUBFOLDER + PROJECT_NAME + LOG_ROLLOVER_SPEC;
        // xml file handler
        try {
            xmlHandler = new FileHandler( baseName + XML_LOGFILE_TYPE, LOGFILE_MAX_BYTES, MAX_NUM_LOG_FILES );
            xmlHandler.setFormatter( new XMLFormatter() );
        }
        catch( Exception e ) {
            System.err.println("xmlHandler exception: " + e);
        }
        // text file handler
        try {
            textHandler = new FileHandler( baseName + TEXT_LOGFILE_TYPE, LOGFILE_MAX_BYTES, MAX_NUM_LOG_FILES );
            textHandler.setFormatter( new MhsFormatter() );
        }
        catch( Exception e ) {
            System.err.println("textHandler exception: " + e);
        }
    }

    /**
     * Set up the root Logger, which handles Console messages <br>
     * <em>called by</em> {@link LogControl#LogControl(String)}
     * @param lev to display at
     * @see LogManager#getLogger
     * @see Logger#getHandlers
     * @see Handler#setFormatter
     */
    private void setRootLogger( Level lev ) {
        consoleLevel = lev;
        consoleIntLevel = consoleLevel.intValue();
        rootLogger = LogManager.getLogManager().getLogger("");
        try {
            Handler[] arH = rootLogger.getHandlers();
            // root logger has one default ConsoleHandler
            System.out.println("LogControl.setRootLogger >> root logger has " + arH.length + " handler(s):");
            if( arH.length == 0 ) {
                System.err.println("LogControl.setRootLogger >> root Logger has NO handlers!\n");
                // TODO: set up a new ConsoleHandler?
            }
            // set formatter and level for the root handler(s)
            for( Handler h : arH ) {
                System.out.println( "\t" + h.toString() );
                // send root handler(s) output to a PskFormatter
                h.setFormatter( new MhsFormatter() );
                // need to set the level of the root handler(s) to control the amount of console logging
                h.setLevel(consoleLevel);
            }
            System.out.println();
        }
        catch( Exception e ) {
            System.err.println("rootLogger exception: " + e);
        }
        rootLogger.severe( "INITIAL console log level = " + consoleLevel );
    }

    /**
     * @return {@link #myLogger}
     */
    MhsLogger getLogger() { return myLogger; }

    /**
     * @param lev {@link Level} to check
     * @return true if console logging is set to this Level
     */
    static boolean atLevel(final Level lev) { return( consoleIntLevel == lev.intValue() ); }

    /** @return true if console logging is set to {@link Level#SEVERE} else false */
    static boolean atSevere() { return atLevel(Level.SEVERE); } // 1000

    /** @return true if console logging is set to {@link Level#WARNING} else false */
    static boolean atWarning() { return atLevel(Level.WARNING); } // 900

    /** @return true if console logging is set to {@link Level#INFO} else false */
    static boolean atInfo() { return atLevel(Level.INFO); } // 800

    /** @return true if console logging is set to {@link Level#CONFIG} else false */
    static boolean atConfig() { return atLevel(Level.CONFIG); } // 700

    /**
     * @param exact true = test at Level FINE; false = test at Level FINE or below
     * @return true if console logging is set to {@link Level#FINE} (or below) else false
     */
    static boolean atFine(Boolean exact) {
        if( exact )
            return atLevel(Level.FINE);
        return consoleIntLevel <= Level.FINE.intValue();
    } // 500

    /** @return true if console logging is set to {@link Level#FINER} else false */
    static boolean atFiner() { return atLevel(Level.FINER); } // 400

    /** @return true if console logging is set to {@link Level#FINEST} else false */
    static boolean atFinest() { return atLevel(Level.FINEST); } // 300

    /**
     * Change the amount of information logged <br>
     * > which means we must DECREASE or INCREASE the {@link Level} of {@link #rootLogger} and {@link #myLogger}<br>
     * @param more INCREASE the amount of logging if <em>true</em>, DECREASE if <em>false</em>
     * @return {@link #consoleLevel}
     * @see Logger#setLevel(Level)
     * @see Handler#setLevel(Level)
     */
    Level changeLogging(final boolean more) {
        int $diff = 100;
        if( more ) {
            if( atFinest() )
                System.out.println("ALREADY at MAXIMUM amount of logging!");
            else if( atConfig() )
                consoleIntLevel = Level.FINE.intValue(); // jump gap from CONFIG to FINE
            else
                consoleIntLevel -= $diff; // go down to a finer (more logging) setting
        }
        else { // less logging
            if( atSevere() )
                System.out.println("ALREADY at MINIMUM amount of logging!");
            else if( atFine(false) )
                consoleIntLevel = Level.CONFIG.intValue(); // jump gap from FINE to CONFIG
            else
                consoleIntLevel += $diff; // go up to a coarser (less logging) setting
        }
        // modify the file logging level if necessary; file logging will NEVER be less than the DEFAULT level
        if( consoleIntLevel >= Level.INFO.intValue() ) fileLevel = DEFAULT_FILE_LEVEL;
        else if( atConfig() ) fileLevel = Level.FINE;
        else if( consoleIntLevel < Level.CONFIG.intValue() ) fileLevel = Level.parse(Integer.toString(consoleIntLevel-$diff));
        if( atFinest() ) fileLevel = Level.FINEST;

        // setting the level of the PSK logger directly works to change the amount of logging
        myLogger.setLevel( fileLevel );
        myLogger.severe( "File log level is NOW at " + fileLevel );

        consoleLevel = Level.parse( Integer.toString(consoleIntLevel) );
        // to change the amount of logging for the root logger, have to change the level of the handler(s)
        for( Handler h : rootLogger.getHandlers() )
            h.setLevel( consoleLevel );
        rootLogger.severe( "Console log level is NOW at " + consoleLevel );

        return consoleLevel;
    }

    //  DEBUGGING
    // //////////////////////////////////////////////

    /**
     * Display list of registered {@link Logger}s
     * @see LogManager#getLoggerNames
     */
    static void showLoggers() {
        String name;
        System.out.println("\nLogControl.showLoggers >>\nCurrently registered Loggers:");
        for( Enumeration<String> e = LogManager.getLogManager().getLoggerNames(); e.hasMoreElements(); ) {
            name = e.nextElement();
            System.out.println( "\t" + (name.isEmpty() ? "<root>" : name) );
        }
        System.out.println(">>> END OF LOGGERS LIST.\n");
    }

    /**
     * Display information about active {@link Logger}s
     * @see LogControl#loggerInfo(Logger,String)
     * @see LogManager#getLogger(String)
     */
    static void checkLogging() {
        loggerInfo( myLogger, "package" );
        loggerInfo( rootLogger, "root" );
        // check the global logger too - just in case
        loggerInfo( LogManager.getLogManager().getLogger("global"), "global" );
        System.out.println(">>> END OF checkLogging().\n");
    }

    /**
     * Display the <var>name</var>, {@link Level}, {@link Handler}s, and {@link Formatter}s of the submitted {@link Logger}
     * @param lgr logger to query
     * @param lgr_name name of logger
     * @see Logger#getHandlers()
     * @see Handler#getFormatter()
     */
    static void loggerInfo( final Logger lgr, final String lgr_name ) {
        if( lgr == null ) {
            System.err.println("LogControl.loggerInfo >> passed Logger is null!");
            return;
        }
        System.out.println( "\nLogControl.loggerInfo >>\n" + (lgr_name.isEmpty() ? "<empty>" : lgr_name) + " = '"
                            + lgr.getName() + "' @ Level = " + lgr.getLevel() );
        Handler[] $handlerAr = lgr.getHandlers();
        System.out.println( "Has " + $handlerAr.length + " handlers:" );
        for( Handler h : $handlerAr )
            System.out.println( "\t<" + h.toString() + "> with Formatter <" + h.getFormatter().toString()
                                + "> @ Level = " + h.getLevel().getName() );
    }

    /*
     *        F I E L D S
     ************************************************************************************************************ */

    /** default value */
    static final int MAX_NUM_LOG_FILES = 256,
            LOGFILE_MAX_BYTES = 1024 * 1024;

    /** {@link Level} to print initialization messages = Level.SEVERE */
    static final Level INIT_LEVEL = Level.INFO;

    /** {@link Level} to print initialization messages = Level.SEVERE */
    static final Level ERROR_LEVEL = Level.WARNING;

    /** default {@link Level} for <b>file</b> logging if NO value passed to Constructor from main class */
    static final Level DEFAULT_FILE_LEVEL = Level.CONFIG;

    /** default {@link Level} for <b>console</b> logging if NO value passed to Constructor from main class */
    static final Level DEFAULT_CONSOLE_LEVEL = Level.WARNING;

    /** default Log name parameter */
    static final String
                PROJECT_NAME  = Sim68k.class.getSimpleName(),
                LOG_SUBFOLDER = "logs/",
                LOG_ROLLOVER_SPEC = "_%u-%g",
                XML_LOGFILE_TYPE  = ".xml",
                TEXT_LOGFILE_TYPE = ".log";

    /** @see MhsLogger */
    private static MhsLogger myLogger;

    /**
     * Can access the ConsoleHandler via the root Logger <br>
     * - ALL logging is sent to a default ConsoleHandler
     * @see Logger
     * @see ConsoleHandler
     */
    private static Logger rootLogger;

    /** @see FileHandler */
    private static FileHandler textHandler, xmlHandler;

    /** current {@link Level} for file logging */
    private static Level fileLevel;

    /** current {@link Level} for console logging */
    private static Level consoleLevel;

    /** integer value of {@link #consoleLevel} */
    private static int consoleIntLevel;
}

/* ================================================================================================================== */

/**
 *  Perform all the Pseudokeu logging operations
 *  @author Mark Sattolo
 */
@SuppressWarnings({"SameParameterValue", "unused"})
class MhsLogger extends Logger {
    /*
     *        C O N S T R U C T O R S
     ************************************************************************************************************ */

    /**
     * <b>Usual</b> constructor - just calls the super equivalent constructor
     * @param name may be <var>null</var>
     * @param resourceBundleName may be <var>null</var>
     * @see Logger#Logger(String,String)
     */
    private MhsLogger(final String name, final String resourceBundleName) {
        super(name, resourceBundleName);
    }

    /*
     *        M E T H O D S
     *********************************************************************************************************** */

    // ==============================================
    //  I N T E R F A C E
    // ==============================================

    /**
     * Allow other package classes to create a {@link Logger} <br>
     * registers the new {@link Logger} with the {@link LogManager}
     * @param name identify the {@link Logger}
     * @return the <b>new</b> {@link Logger}
     * @see LogManager#addLogger(Logger)
     */
    protected static synchronized MhsLogger getNewLogger(final String name) {
        MhsLogger $logger = new MhsLogger(name, null);
        LogManager.getLogManager().addLogger($logger);
        return $logger;
    }

    /**
     * Prepare and send a customized {@link LogRecord} for an ERROR condition
     * @param msg the text to insert in the {@link LogRecord}
     */
    protected void logError(final String msg, int offset) {
        System.err.println( Arrays.toString(Thread.currentThread().getStackTrace()) );
        int lineNum = Thread.currentThread().getStackTrace()[2+offset].getLineNumber();
        if( (callclass == null) || (callmethod == null) ) {
            getCallerClassAndMethodName();
        }
        LogRecord $logRec = getRecord( LogControl.ERROR_LEVEL, "LINE #" + lineNum + MhsFormatter.NLN + msg );
        sendRecord($logRec);
    }

    /**
     * Prepare and send a customized {@link LogRecord} for an ERROR condition
     * @param msg the text to insert in the {@link LogRecord}
     */
    protected void logError(final String msg) {
        logError( msg, 1 );
    }

    /**
     * Prepare and send a customized {@link LogRecord} for an <em>initialization</em> method
     * @param msg the text to insert in the {@link LogRecord}
     */
    protected void logInit(final String msg) {
        if( (callclass == null) || (callmethod == null) ) {
            getCallerClassAndMethodName();
        }
        LogRecord $logRec = getRecord( LogControl.INIT_LEVEL, "<INIT> " + msg );
        sendRecord($logRec);
    }

    /**
     * Prepare and send a basic {@link LogRecord} for an <em>initialization</em> method
     */
    protected void logInit() {
        getCallerClassAndMethodName();
        logInit("0");
    }

    /**
     * Prepare and send a {@link LogRecord} with data from {@link MhsLogger#buffer}
     * @param lev {@link Level} to log at
     */
    protected void send(final Level lev) {
        if( buffer.length() == 0 )
            return;
        getCallerClassAndMethodName();
        LogRecord $logRec = getRecord( lev, buffer.toString() );
        clean();
        sendRecord($logRec);
    }

    /**
     * Add data to {@link MhsLogger#buffer}
     * @param msg data String
     */
    protected synchronized void append(final String msg) { buffer.append(msg); }

    /**
     * Add data to {@link MhsLogger#buffer} with newline
     * @param msg data String
     */
    protected void appendln(final String msg) { append(msg + "\n"); }

    /**
     * Add newline to {@link MhsLogger#buffer}
     */
    protected void appendln() { append("\n"); }

    /**
     * <b>Remove</b> <em>ALL</em> data in {@link MhsLogger#buffer}
     */
    protected void clean() { buffer.delete( 0, buffer.length() ); }

    // DEBUGGING
    // //////////////////////////////////////////////

    /*/
    @Override
    void log( LogRecord record )
    {
      System.out.println( "---------------------------------------------------" );
      System.out.println( "record Message is '" + record.getMessage() + "'" );
      System.out.println( "record Class caller is '" + record.getSourceClassName() + "'" );
      System.out.println( "record Method caller is '" + record.getSourceMethodName() + "'" );
      super.log( record );
    }
    //*/

    // ==============================================
    //  P R I V A T E
    // ==============================================

    /**
     * Provide a <b>new</b> {@link LogRecord} with Caller class and method name info
     * @param level {@link Level} to log at
     * @param msg info to insert in the {@link LogRecord}
     * @return the produced {@link LogRecord}
     */
    private LogRecord getRecord(final Level level, final String msg) {
        LogRecord $logRec = new LogRecord( (level == null ? LogControl.DEFAULT_FILE_LEVEL : level), msg );
        $logRec.setSourceClassName(callclass);
        $logRec.setSourceMethodName(callmethod);
        return $logRec;
    }

    /**
     * Actually send the {@link LogRecord} to the logging handler
     * @param rec {@link LogRecord} to send
     * @see Logger#log(LogRecord)
     */
    private synchronized void sendRecord(LogRecord rec) {
        callclass = null;
        callmethod = null;
        super.log(rec);
    }

    /**
     * Get the name of the {@link Class} and <em>Method</em> that called
     * @see Throwable#getStackTrace
     * @see StackTraceElement#getClassName
     * @see StackTraceElement#getMethodName
     */
    private void getCallerClassAndMethodName() {
        Throwable $t = new Throwable();
        StackTraceElement[] $elementAr = $t.getStackTrace();
        if( $elementAr.length < 3 ) {
            callclass = callmethod = strUNKNOWN;
        }
        else {
            callclass = $elementAr[2].getClassName();
            callmethod = $elementAr[2].getMethodName();
        }
    }

    /*
     *        F I E L D S
     ************************************************************************************************************ */

    /** <em>Class</em> calling the Logger */
    private String callclass = null;

    /** <em>method</em> calling the Logger */
    private String callmethod = null;

    /**
     * Store info from multiple {@link MhsLogger#append} or {@link MhsLogger#appendln} calls <br>
     * for a 'bulk send'
     * @see StringBuilder
     */
    private final StringBuilder buffer = new StringBuilder(1024);

    /** default if cannot get method or class name */
    static final String strUNKNOWN = "unknown";
}

/* ================================================================================================================== */

/**
 *  Do all the actual {@link LogRecord} formatting for {@link MhsLogger}
 *  @author Mark Sattolo
 */
@SuppressWarnings("unused")
class MhsFormatter extends Formatter {
    /** Instructions on how to format a {@link LogRecord} */
    @Override
    public String format(LogRecord rec) {
        return( rec.getLevel() + REC + (++count) + NLN + rec.getSourceClassName() + SPC
                + rec.getSourceMethodName() + NLN + rec.getMessage() + NLN + NLN );
    }

    /** Printed at the beginning of a Log file */
    @Override
    public String getHead(Handler h) {
        return(NLN + DIV + NLN + HEAD + DateFormat.getDateTimeInstance().format(new Date()) + NLN + DIV + NLN + NLN);
    }

    /** Printed at the end of a Log file */
    @Override
    public String getTail(Handler h) {
        return(DIV + NLN + TAIL + DateFormat.getDateTimeInstance().format(new Date()) + NLN + DIV + NLN + NLN);
    }

    /** Number of times {@link MhsFormatter#format(LogRecord)} has been called */
    private int count;

    /** useful String constant */
    static final String
            SPC = " ",
            NLN    = "\n",
            DIV    = "==================================================================",
            SM_DIV = "-----------------------------------------",
            REC    = ": " + LogControl.PROJECT_NAME + " record #",
            HEAD   = LogControl.PROJECT_NAME + " START" + NLN,
            TAIL   = LogControl.PROJECT_NAME + " END" + NLN;
}
