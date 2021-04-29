/* ******************************************************************************
 *
 *  Sim68k.java
 *
 *  Ported from Sim68k.cc - originally a Pascal program created in Nov 1999 for CSI2111
 *                        - simulates the functioning of the Motorola 68000 microprocessor
 *
 *  Copyright (c) 2021 Mark Sattolo <epistemik@gmail.com>
 *
 *  Java version created 2021-04
 *
 ********************************************************************************/

import java.io.File;
import java.util.Locale;
import java.util.Scanner;

/**
 *  originally a Pascal program created in Nov 1999 for CSI2111 <br>
 *  simulates the functioning of the Motorola 68000 microprocessor
 */
class Sim68k {
    /*
     *   DATA DEFINITIONS
     * ========================================================================================================= */

    final static String
        COMMENT_MARKER = "/" ,
        QUIT    = "q" ,
        EXECUTE = "e" ,
        TEST    = "t" ;

    final static char HEX_MARKER = '$' ;

    /** List of OpId = OpCode */
    final static int iADD  =  0 , // Regular binary integer addition
                     iADDQ =  1 , // Quick binary integer addition
                     iSUB =   2 , // Regular binary integer subtraction
                     iSUBQ =  3 , // Quick binary integer subtraction
                     iMULS =  4 , // Signed binary multiplication
                     iDIVS =  5 , // Signed binary division
                     iNEG =   6 , // Signed binary negation
                     iCLR =   7 , // Clear (set to 0)
                     iNOT =   8 , // Logical NOT
                     iAND =   9 , // Logical AND
                     iOR  =  10 , // Logical OR
                     iEOR =  11 , // Logical EXCLUSIVE-OR
                     iLSL =  12 , // Logical Shift Left
                     iLSR =  13 , // Logical Shift Right
                     iROL =  14 , // Rotate Left
                     iROR =  15 , // Rotate Right
                     iCMP =  16 , // Compare (to adjust CVNZH according to D-S)
                     iTST =  17 , // Test    (to adjust CVNZH according to D)
                     iBRA =  18 , // Unconditional branch to the given address
                     iBVS =  19 , // Branch to the given address if overflow
                     iBEQ =  20 , // Branch to the given address if equal
                     iBCS =  21 , // Branch to the given address if carry
                     iBGE =  22 , // Branch to the given address if greater | equal
                     iBLE =  23 , // Branch to the given address if less | equal
                     iMOV =  24 , // Regular move
                     iMOVQ = 25 , // Quick move
                     iEXG =  26 , // Exchange 2 registers
                     iMOVA = 27 , // Move the given address into A[i]
                     iINP =  28 , // Read from keyboard (input)
                     iDSP =  29 , // Display the name, the source & the contents
                     iDSR =  30 , // Display the contents of the Status booleans
                     iHLT =  31 ; // HALT program

    /** identify the Least OR Most Significant bit/byte/word of a number */
    static final boolean LEAST = false,
                          MOST = true ;
    /** determine memory access of Reading OR Writing */
    static final boolean WRITE = false,
                          READ = true ;
    /** identify which byte within a long */
    enum TwoBits { byte0, byte1, byte2, byte3 }

    /**
     *  Indicate to an operation to use byte, word or long <br>
     *  Original requirements: <br>
     *    A "byte" represents 2 hexadecimal digits ($00...$FF).<br>
     *    A "word" represents 2 bytes ($0000...$FFFF).<br>
     *    A "long" represents a long word which is 2 words, i.e., 4 bytes ($0000 0000 ... $FFFF FFFF).<br>
     *    >> If a byte, word or long word represents data (i.e., a value)<br>
     *       then it is interpreted as a signed binary integer in 2's CF.
     */
    enum DataSize {
        /** 8 bits */
        ByteSize((byte)1, "byte"),
        /** 16 bits */
        WordSize((byte)2, "word"),
        /** 32 bits */
        LongSize((byte)4, "long");

        /** in bytes */
        private final byte size;
        /** 'byte', 'word' or 'long' */
        private final String name;

        DataSize(byte sz, String nom) {
            this.size = sz;
            this.name = nom;
        }

        byte sizeValue() { return size; }
        String strValue() { return name; }
    }
    /** get the proper DataSize from the value in the OpCode */
    static DataSize getDataSize(int code) {
        if( code == 0 ) return DataSize.ByteSize;
        if( code == 1 ) return DataSize.WordSize;
        if( code == 2 ) return DataSize.LongSize;
        logger.logError( "INVALID DATA SIZE!" );
        return null ;
    }

    /** several different addressing modes available for this cpu */
    enum AddressMode {
        DATA_REGISTER_DIRECT,
        ADDRESS_REGISTER_DIRECT,
        AM_TWO_UNUSED,
        RELATIVE_ABSOLUTE,
        ADDRESS_REGISTER_INDIRECT,
        AM_FIVE_UNUSED,
        ADDRESS_REGISTER_INDIRECT_POSTINC,
        ADDRESS_REGISTER_INDIRECT_PREDEC
    }
    /** get the proper AddressMode from the value in the OpCode */
    static AddressMode getAddressMode(int code) {
        if( code == 0 ) return AddressMode.DATA_REGISTER_DIRECT ;
        if( code == 1 ) return AddressMode.ADDRESS_REGISTER_DIRECT ;
        if( code == 2 ) {
            logger.warning( "UNUSED ADDRESS MODE: AM_TWO_UNUSED!" );
            return AddressMode.AM_TWO_UNUSED ;
        }
        if( code == 3 ) return AddressMode.RELATIVE_ABSOLUTE ; // i.e. relative OR absolute addressing
        if( code == 4 ) return AddressMode.ADDRESS_REGISTER_INDIRECT ;
        if( code == 5 ) {
            logger.warning( "UNUSED ADDRESS MODE: AM_FIVE_UNUSED!" );
            return AddressMode.AM_FIVE_UNUSED ;
        }
        if( code == 6 ) return AddressMode.ADDRESS_REGISTER_INDIRECT_POSTINC ;
        if( code == 7 ) return AddressMode.ADDRESS_REGISTER_INDIRECT_PREDEC ;
        logger.logError( "NO ADDRESS MODE for code = " + code + "!" );
        return null ;
    }

    /** hex values 0x0000 to 0x1000 */
    static final int MemorySize = 0x1001 ;

    /** Mnemonic String for opCodes */
    static String[] Mnemo ;

    /** Logging management */
    protected static LogControl logControl;

    /** Logging actions */
    protected static MhsLogger logger;

    /*
     *   HARDWARE
     * ========================================================================================================= */

    /** program storage */
    private Memory mem;

    // The CPU registers
    /** Program Counter */
    short PC ;
    /** OPCODE of the current instruction */
    short OpCode ;
    /** Operand Addresses */
    short OpAddr1, OpAddr2 ;

    // status bits
    /** Carry */
    boolean C ;
    /** Overflow */
    boolean V ;
    /** Zero */
    boolean Z ;
    /** Negative */
    boolean N ;
    /** Halt program execution */
    boolean H ;

    /** two Data Registers */
      int[] DR;
    /** two Address Registers */
    short[] AR;

    /** Memory Address Register */
    short MAR ;
    /** Memory Data Register */
    int   MDR ;

    /** Temporary Registers Dest, Src, Result */
    TempReg TMPD, TMPS, TMPR;

    /* ***************************************************************************

      Functions for bit manipulation
     ================================
     Pascal, unlike C, does not have operators that allow easy manipulation
     of bits within a byte or short. The following procedures and functions
     are designed to help you manipulate (extract and set) the bits.
     You may use or modify these procedures/functions or create others.

    ****************************************************************************** */

    /**
     *  Return a subString of bits between FirstBit and LastBit from a word <br>
        Ex: <br>
          Bit Positions: 15-12 11-8  7-4   3-0 <br>
          wV = 0x1234 =  0001  0010  0011  0100 <br>
          FirstBit = 3, LastBit = 9 <br>
          The bits from 3 to 9 are:  10 0011 0 <br>
          So the function returns 0x0046 (0000 0000 0100 0110)
     */
    short getBits( final int wV, final int FirstBit, final int LastBit) {
        return (short)( (wV >> FirstBit) & ((2 << (LastBit - FirstBit)) - 1) );
    }

    /** In an int set the bit indicated by posn to val (false or true) */
    int setBit(int nV, final short posn, final boolean val) {
        byte bt = (val) ? (byte)1 : (byte)0 ;
        return (nV & (0xFFFFFFFF - (1 << posn))) | (bt << posn) ;
    }

    /** In an int set the bits between first and last to the least significant bits of val */
    int setBits(int nV, final byte first, final byte last, final short val) {
        short pos;
        int result = nV;
        for( pos = first; pos <= last; pos++ )
            result = setBit( result, pos, (getBits(val, (byte)(pos - first), (byte)(pos - first)) == 1) );
        return result;
    }

    /** In an int set one byte indicated by posn to val */
    int setByte(int nV, final TwoBits posn, final byte val) {
        switch (posn) {
            case byte0 -> { return (nV & 0xFFFFFF00) | val; }
            case byte1 -> { return (nV & 0xFFFF00FF) | (val << 8); }
            case byte2 -> { return (nV & 0xFF00FFFF) | (val << 16); }
            case byte3 -> { return (nV & 0x00FFFFFF) | (val << 24); }
        }
        return 0; // should NEVER reach here
    }

    /* ***************************************************************************

      The word, i.e. short (16 bit) utility functions are problematic
      as Java silently promotes any short used in a bit shift operation
      to an int, WITH sign extension!!
      >> and in some cases this DOES NOT give the required result

     ***************************************************************************** */

    /**
     *  GET most or least significant word from an int <br>
     *  MSW: false = Least Significant Word, true = Most Significant Word
     */
    int getWord(final int nV, boolean MSW) {
        logger.finer( "nV = " + nV + " | " + intHex(nV) );
        if( MSW )
            return( nV >>> 16 );
        int nvl = nV << 16 ;
        logger.finer( "nvl = " + nvl + " | " + intHex(nvl) );
        int nvr = nvl >>> 16 ;
        logger.finer( "nvr = " + nvr + " | " + intHex(nvr) );
        return nvr ;
    }

    /**
     *  In an int SET one word indicated by MSW to val <br>
     *  MSW: false = Least Significant Word, true = Most Significant Word <br>
     *  NEED this version for the fill() and write() methods of <em>TempReg</em>
     */
    int setWord(int nV, final boolean MSW, final int val) {
        logger.finer( "nV = " + nV + " | " + intHex(nV) );
        logger.finer( "val = " + val + " | " + intHex(val) );
        if( MSW ) {
            int nvmod = nV & 0x0000FFFF ;
            logger.finer( "nvmod = " + nvmod + " | " + intHex(nvmod) );
            short valmod = (short)(val << 16);
            logger.finer( "valmod = " + valmod + " | " + intHex(valmod) );
            return( nvmod | valmod );
        }
        int nvmod = nV & 0xFFFF0000 ;
        logger.finer( "nvmod = " + nvmod + " | " + intHex(nvmod) );
        return( nvmod | val );
    }

    /**
     *  In an int SET one word indicated by MSW to val <br>
     *  MSW: false = Least Significant Word, true = Most Significant Word <br>
     *  NEED this version for <em>signed division</em>
     */
    int setShort(int nV, final boolean MSW, final short val) {
        if( MSW )
            return (nV & 0x0000FFFF) | (val << 16) ;
        return (nV & 0xFFFF0000) | val ;
    }

    /* ***************************************************************************

       Functions to display values as hex and/or binary numbers

     ***************************************************************************** */

    /** utility function for easy display of a byte value as an unsigned hex String */
    static String byteInHex(byte value) {
        return intHex( Byte.toUnsignedInt(value) );
    }

    /** utility function for easy display of an int value as UNSIGNED hex String */
    static String intHex(int value) {
        return( HEX_MARKER + Integer.toHexString(value).toUpperCase(Locale.ROOT) );
    }

    /** utility function for easy display of an int value as UNSIGNED hex AND bit String */
    static String intHexBin(int value) {
        return( value + " | " + intHex(value) + " | " + Integer.toBinaryString(value) );
    }

    /*
     *   INNER CLASSES
     * ========================================================================================================= */

    /** Temporary Registers */
    class TempReg {
        TempReg() {
            logger.logInit();
        }
        private int value;

        void set(int val) { value = val; }
        int get() { return value; }
        /** display value as a hex String */
        String hex() { return( value + " | " + HEX_MARKER + Integer.toHexString(value).toUpperCase(Locale.ROOT) ); }

        /** set value to the sum of the values in the parameters */
        void add(TempReg trA, TempReg trB) { set( trA.get() + trB.get() ); }
        /** set value to the difference of the values in the parameters */
        void subtract(TempReg trA, TempReg trB) { set( trA.get() - trB.get() ); }
        /** set value to the product of the values in the parameters */
        void multiply(TempReg trA, TempReg trB) { set( trA.get() * trB.get() ); }

        /* ***************************************************************************

         Since many instructions will make local fetches between temporary registers
         (TMPS, TMPD, TMPR) & memory or the Dn & An registers it would be
         useful to create procedures to transfer the shorts/bytes between them.
         Here are 2 suggestions of procedures to do this.

         **************************************************************************** */

        /**
         *  Transfer the contents of a CPU Register OR Memory to this temporary register
         *  @param opAddr  address of Operand for addressMode RELATIVE_ABSOLUTE
         *  @param    dsz  Data Size
         *  @param   mode  required Addressing Mode
         *  @param  regNo  Register number for A[n] or D[n]
         */
        void fill( short opAddr, DataSize dsz, AddressMode mode, byte regNo ) {
            logger.info( "Operand address = " + opAddr + "; dsz = " + dsz.strValue()
                            + "; adrMode = " + mode + "; RegNo = " + regNo );
            switch (mode) {
                case DATA_REGISTER_DIRECT -> {
                    set( DR[regNo] );
                    if( dsz == DataSize.ByteSize )
                        set( setByte(get(), TwoBits.byte1, (byte)0) );
                    if( dsz.sizeValue() <= DataSize.WordSize.sizeValue() ) {
                        set( setWord(get(), MOST, 0) );
                    }
                }
                case ADDRESS_REGISTER_DIRECT -> set( AR[regNo] );

                case RELATIVE_ABSOLUTE -> {
                    // We need to access memory, except for branching & MOVA.
                    MAR = opAddr;
                    mem.access( dsz, READ );
                    set( MDR );
                }
                case ADDRESS_REGISTER_INDIRECT -> {
                    // We need to access memory.
                    MAR = AR[regNo];
                    mem.access( dsz, READ );
                    set( MDR );
                }
                case ADDRESS_REGISTER_INDIRECT_POSTINC -> {
                    // We need to access memory.
                    MAR = AR[regNo];
                    mem.access( dsz, READ );
                    set( MDR );
                    AR[regNo] = (short)( AR[regNo] + dsz.sizeValue() );
                }
                case ADDRESS_REGISTER_INDIRECT_PREDEC -> {
                    // We need to access memory.
                    AR[regNo] = (short)( AR[regNo] - dsz.sizeValue() );
                    MAR = AR[regNo];
                    mem.access( dsz, READ );
                    set( MDR );
                }
                default -> { // This should never occur, but just in case...!
                    logger.logError( "\n>>> INVALID Addressing Mode '" + mode + "' at PC = " + (PC-2) );
                    H = true;
                }
            }
        }

        /**
         *  Transfer the contents of this temporary register to a CPU Register OR Memory
         *  @param OpAddr  address of Operand for addressMode RELATIVE_ABSOLUTE
         *  @param    dsz  Data Size
         *  @param   mode  required Addressing Mode
         *  @param  RegNo  Register Number for A[n] or D[n]
         */
        void write( short OpAddr, DataSize dsz, AddressMode mode, byte RegNo ) {
            logger.info( "Operand address = " + OpAddr + "; dsz = " + dsz.strValue()
                            + "; adrMode = " + mode + "; RegNo = " + RegNo );
            switch (mode) {
                case DATA_REGISTER_DIRECT -> {
                    switch (dsz) {
                        case ByteSize -> DR[RegNo] = setBits( DR[RegNo], (byte)0, (byte)7, (short)get() );
                        case WordSize -> {
                            int dr = DR[RegNo];
                            int gwl = getWord( get(), LEAST );
                            int swd = setWord( dr, LEAST, gwl );
                            logger.fine( "new DR = " + swd + " | " + intHex(swd) );
                            DR[RegNo] = swd;
                        }
                        case LongSize -> DR[RegNo] = get();
                        default -> {
                            logger.logError( "\n>>> INVALID data size '" + dsz + "' at PC = " + (PC-2) );
                            H = true;
                        }
                    }
                    logger.fine( "DR[" + RegNo + "] now = " + Sim68k.intHexBin( DR[RegNo] ) );
                }
                case ADDRESS_REGISTER_DIRECT -> AR[RegNo] = (short)getWord( get(), LEAST );

                // We need to access memory, except for branching & MOVA
                case RELATIVE_ABSOLUTE -> {
                    MAR = OpAddr;
                    MDR = get();
                    mem.access( dsz, WRITE );
                    logger.fine( "Memory at " + MAR + " = " + mem.show(MAR) );
                }
                // We need to access memory
                case ADDRESS_REGISTER_INDIRECT, ADDRESS_REGISTER_INDIRECT_PREDEC -> {
                    // ATTENTION: for some instructions, the address register has already been decremented by fillTmpReg
                    // DO NOT decrement it a 2nd time here
                    MAR = AR[RegNo];
                    MDR = get();
                    mem.access( dsz, WRITE );
                }
                // We need to access memory.
                case ADDRESS_REGISTER_INDIRECT_POSTINC -> {
                    // ATTENTION: for some instructions, the address register has already been incremented by fillTmpReg()
                    // DO NOT increment it a 2nd time here
                    MAR = (short)( AR[RegNo] - dsz.sizeValue() );
                    MDR = get();
                    mem.access( dsz, WRITE );
                }
                default -> {
                    logger.logError( "\n>>> INVALID Addressing Mode '" + mode + "' at PC = " + (PC-2) );
                    H = true;
                }
            }
        }
    }

    /** store information */
    class Memory {
        Memory() {
            logger.logInit();
            memory = new byte[MemorySize];
        }

        /** store the binary program */
        private final byte[] memory;

        /** load the binary program to memory */
        void load( short location, byte data ) {
            logger.fine("Read value " + byteInHex(data) + " into memory at location: " + location);
            memory[location] = data ;
        }

        /** display a memory byte value as a hex String */
        String show(int place) { return byteInHex( memory[place] ); }

        /**
         *  Copies an element (Byte, Word, Long) from memory to MDR <b>OR</b> MDR to memory <br>
         *  Verifies if we are trying to access an address outside the allowed range [0x0000..0x1000]
         *  @param RW  determines if the access is READ (true) or WRITE (false)
         *  @param dsz determines the data size (byte, word, long)
         */
        void access(DataSize dsz, boolean RW ) {
            if( MAR >= MemorySize ) { // INVALID Memory Address
                logger.logError("\n*** INVALID address: " + MAR);
                H = true ; // End of simulation...!
                return;
            }
            if( RW ) { // true = READ = copy an element from memory to the CPU's MDR
                switch (dsz) {
                    case ByteSize -> MDR = memory[MAR];
//                    case wordSize -> MDR = memory[MAR] * 0x100 + memory[MAR + 1];
                    // addition of memory addresses DOES NOT WORK because Java only does SIGNED addition
                    case WordSize -> {
                        byte mem0 = memory[MAR] ;
                        byte mem1 = memory[MAR+1] ;
                        logger.fine( "Memory[" + MAR + "] = " + mem0 + " | " + byteInHex(mem0) );
                        logger.fine( "Memory[" + (MAR+1) + "] = " + mem1 + " | " + byteInHex(mem1) );
                        int mdrt1 = ( (mem0 * 0x100) & 0x0000FF00 ) | ( mem1 & 0x000000FF );
                        logger.fine( "mdrt1 = " + mdrt1 + " | " + intHex(mdrt1) );
                        MDR = mdrt1 ;
                    }
                    case LongSize -> MDR = ( (memory[MAR] * 0x1000000) & 0xFF000000 ) |
                                           ( (memory[MAR+1] * 0x10000) & 0x00FF0000 ) |
                                           ( (memory[MAR+2] * 0x100) & 0x0000FF00 ) |
                                           (  memory[MAR+3] & 0x000000FF );
                    default -> {
                        logger.logError("\n*** INVALID data size: " + dsz.strValue());
                        H = true;
                    }
                }
                logger.info("READ of " + dsz.strValue() + ": now MDR = " + MDR + " | "  + intHex(MDR));
                return;
            }
            // false = WRITE = copy an element from the CPU's MDR to memory
            logger.info( "WRITE of " + dsz.strValue() + ": MAR = " + MAR + " | MDR = " + intHex(MDR) );
            switch (dsz) {
                case ByteSize -> {
                    memory[MAR] = (byte)( MDR % 0x100 ); // LSB: 8 last bits
                    logger.fine( dsz.strValue() + ".WRITE: now memory[" + MAR + "] = " + byteInHex(memory[MAR]) );
                }
                case WordSize -> {
//                    memory[MAR]   = (byte)( (MDR / 0x100) % 0x100 ); // MSB: 8 first bits
                    // division DOES NOT WORK because Java rounds up to next integer instead of just dropping the fraction part
                    byte mdrb = (byte)( (MDR >> 8) & 0x000000FF );
                    logger.finer( "mdrb = " + mdrb + " | " + intHex(mdrb) );
                    memory[MAR]   = mdrb; // MSB: 8 first bits
                    memory[MAR+1] = (byte)( MDR % 0x100 ); // LSB: 8 last bits
                    logger.fine( dsz.strValue()
                                    + ".WRITE: memory[" + MAR + "] now = " + byteInHex(memory[MAR])
                                    + "\n\t\t\tmemory[" + (MAR+1) + "] now = " + byteInHex(memory[MAR+1]) );
                }
                case LongSize -> {
                    memory[MAR]   = (byte)( (MDR >> 24) & 0x000000FF );
                    memory[MAR+1] = (byte)( (MDR >> 16) & 0x000000FF );
                    memory[MAR+2] = (byte)( (MDR >> 8) & 0x000000FF );
                    memory[MAR+3] = (byte)( MDR % 0x100 );
                    logger.fine( dsz.strValue()
                                    + ".WRITE: memory[" + MAR + "] now = " + byteInHex(memory[MAR])
                                    + "\n\t\t\tmemory[" + (MAR+1) + "] now = " + byteInHex(memory[MAR+1])
                                    + "\n\t\t\tmemory[" + (MAR+2) + "] now = " + byteInHex(memory[MAR+2])
                                    + "\n\t\t\tmemory[" + (MAR+3) + "] now = " + byteInHex(memory[MAR+3]));
                }
                default -> {
                    logger.logError( "\n*** INVALID data size: " + dsz.strValue() );
                    H = true;
                }
            }
        }
    }

    /** simulates fetch and execute */
    class Controller {
        Controller() {
            logger.logInit();
            // Temporary Registers Dest, Src, Result
            TMPD = new TempReg();
            TMPS = new TempReg();
            TMPR = new TempReg();
        }
        /** Most Significant Bits of TMPS, TMPD and TMPR */
        boolean Sm, Dm, Rm ;

        /** numeric id for opCodes */
        byte OpId ;
        /** number of necessary operands = opCode bit P+1 */
        byte numOprd ;
        /** store data from opCode for Format F2 */
        byte opcData ;

        /** byte, word or long */
        DataSize DS ;
        /** temp storage for Register # (from opCode) for operands 1 and 2 */
        byte R1, R2 ;
        /** temp storage for AddressMode (from opCode) for operands 1 and 2 */
        AddressMode M1, M2 ;

        /**
         *  Generic error verification function, with message display:
         *  if Cond is False, display an error message (including the OpName)
         *  The Halt bit will also be set if there is an Error.
         */
        boolean checkCond( boolean Cond, String Message ) {
            if( Cond )
                return true ;
            logger.logError("'" + Message + "' at PC = " + (PC-2) + " for operation " + Mnemo[OpId], 1);
            H = true ; // program will halt
            return false ;
        }

        /** Fetch the OpCode from memory */
        void fetchOpCode() {
            MAR = PC ;
            logger.info("at mem address = " + MAR);
            PC += 2 ;
            mem.access( DataSize.WordSize, READ );
            logger.info( "MDR = " + intHex(MDR) );
            OpCode = (short)getWord( MDR, LEAST ); // get LSW from MDR
        }

        /** Update the fields OpId, DS, numOprd, M1, R1, M2, R2 and opcData */
        void decodeInstr() {
            logger.info( "OpCode = " + Integer.toBinaryString(OpCode) );
            DS = getDataSize( getBits(OpCode, 9, 10) );
            OpId = (byte)getBits( OpCode, 11, 15 );
            numOprd = (byte)( getBits(OpCode, 8, 8) + 1 );

            logger.config( "OpCode " + intHex(OpCode) + " at PC = " + (PC-2)
                           + " :\n\tOpId = " + Mnemo[OpId] + ", size = " + DS.strValue() + ", numOprnd = " + numOprd );

            if( numOprd > 0 ) { // SHOULD ALWAYS BE TRUE!
                M2 = getAddressMode( getBits(OpCode, 1, 3) );
                R2 = (byte)getBits( OpCode, 0, 0 );

                if( formatF1(OpId) )
                    if( (OpId < iDSR) ) {
                        M1 = getAddressMode( getBits(OpCode, 5, 7) );
                        R1 = (byte)getBits( OpCode, 4, 4 );
                    }
                    else { // NEED to reset these for iDSR and iHLT !
                        M1 = M2 = AddressMode.DATA_REGISTER_DIRECT ;
                        R1 = R2 = 0 ;
                    }
                else // Format F2
                    opcData = (byte)getBits( OpCode, 4, 7 );
            }
            else {
                logger.logError("INVALID number of operands '" + numOprd + "' at PC = " + (PC-2));
                H = true ;
            }
            logger.config("\tM1 = " + M1 + ", M2 = " + M2 + ", R1 = " + R1 + ", R2 = " + R2 + ", opcData = " + opcData);
        }

        /** Fetch the operands, according to their number (numOprd) and addressing modes (M1 or M2) */
        void fetchOperands() {
            logger.info(numOprd + " operands at PC = " + (PC-2) + ": M1 = " + M1 + ", M2 = " + M2);

            // Fetch the address of 1st operand (in OpAddr1)
            if( formatF1(OpId) && (M1 == AddressMode.RELATIVE_ABSOLUTE) ) {
                MAR = PC ;
                mem.access( DataSize.WordSize, READ );
                OpAddr1 = (short)getWord( MDR, LEAST ); // get LSW of MDR
                PC += 2 ;
            }

            // Fetch the address of 2nd operand, if F1 & 2 operands.
            // OR, operand of an instruction with format F2 put in OpAddr2
            if( M2 == AddressMode.RELATIVE_ABSOLUTE ) {
                MAR = PC ;
                mem.access( DataSize.WordSize, READ );
                OpAddr2 = (short)getWord( MDR, LEAST ); // get LSW of MDR
                PC += 2 ;
            }

            // Check invalid number of operands.
            if( numOprd == 2  &&  !formatF1(OpId) ) {
                logger.logError( "INVALID number of operands for " + Mnemo[OpId] + " at PC = " + (PC-2) );
                H = true ;
            }
        }

        /** set Status bits Z (zero) and N (negative) according to the value in tr */
        void setZN( TempReg tr ) {
            int trVal = tr.get();
            switch (DS) {
                case ByteSize -> {
                    short bzw = getBits( (short)getWord(trVal, LEAST), 0, 7 );
                    Z = (bzw == 0);
                    short bnw = getBits( (short)getWord(trVal, LEAST), 7, 7 );
                    N = (bnw == 1);
                }
                case WordSize -> {
                    short wzw = getBits( (short)getWord(trVal, LEAST), 0, 15 );
                    Z = (wzw == 0);
                    short wnw = getBits( (short)getWord(trVal, LEAST), 15, 15 );
                    N = (wnw == 1);
                }
                case LongSize -> {
                    Z = (trVal == 0);
                    short lnw = getBits( (short)getWord(trVal, MOST), 15, 15 );
                    N = (lnw == 1);
                }
                default -> {
                    logger.logError( "INVALID data size '" + DS + "' at PC = " + (PC-2) );
                    H = true;
                }
            }
        }

        /**
         *  Get the most significant bits of TMPD, TMPS and TMPR <br>
         *  The calculations for V (overflow) and C (carry) use these values
         */
        void setSmDmRm( TempReg trS, TempReg trD, TempReg trR ) {
            logger.fine( "DS = " + DS.strValue() );
            byte mostSigBit ; // wordSize
            switch( DS ) {
                case ByteSize -> mostSigBit =  7 ;
                case WordSize -> mostSigBit = 15 ; // wordSize
                case LongSize -> mostSigBit = 31 ;
                default -> {
                    logger.logError( "INVALID data size '" + DS + "' at PC = " + (PC-2) );
                    H = true ;
                    return ;
                }
            }
            Sm = getBits( trS.get(), mostSigBit, mostSigBit ) == 1 ;
            Dm = getBits( trD.get(), mostSigBit, mostSigBit ) == 1 ;
            Rm = getBits( trR.get(), mostSigBit, mostSigBit ) == 1 ;
        }

        /** The execution of each instruction is done via its micro-program */
        void execInstr() {
            logger.config( Mnemo[OpId] + "." + DS.strValue()
                            + ": OpAd1 = " + OpAddr1 + ", OpAd2 = " + OpAddr2
                            + "\n\tM1 = " + M1 + ", R1 = " + R1 + ", M2 = " + M2 + ", R2 = " + R2 );

            /* Execute an instruction according to the OpId from the current opCode
               Use a CASE structure where each case corresponds to an instruction & its micro-program  */
            switch( OpId ) {
                // addition
                case iADD:
                    TMPS.fill( OpAddr1, DS, M1, R1 );
                    TMPD.fill( OpAddr2, DS, M2, R2 );
                    TMPR.add( TMPS, TMPD );
                    logger.info("TMPR(" + TMPR.hex() + ") = TMPS(" + TMPS.hex() + ") + TMPD(" + TMPD.hex() + ")");
                    setZN( TMPR );
                    setSmDmRm( TMPS, TMPD, TMPR );
                    boolean v1 = ( Sm & Dm & !Rm );
                    boolean v2 = ( !Sm & !Dm & Rm );
                    logger.fine( "v1 = " + v1 + "; and v2 = " + v2 );
                    V = v1 | v2 ;
                    logger.fine( "V = " + V );
                    C = ( Sm & Dm ) | ( !Rm & Dm ) | ( Sm & !Rm );
                    TMPR.write( OpAddr2, DS, M2, R2 );
                    break ;
                // add quick
                case iADDQ:
                    TMPD.fill( OpAddr2, DS, M2, R2 );
                    TMPS.set( setByte(0, TwoBits.byte0, opcData) );
                    // Sign extension if W or L ??
                    TMPR.add( TMPD, TMPS );
                    logger.info("TMPR(" + TMPR.hex() + ") = TMPS(" + TMPS.hex() + ") + TMPD(" + TMPD.hex() + ")");
                    setZN( TMPR );
                    setSmDmRm( TMPS, TMPD, TMPR );
                    V = ( Sm & Dm & !Rm ) | ( !Sm & !Dm & Rm );
                    C = ( Sm & Dm ) | ( !Rm & Dm ) | ( Sm & !Rm );
                    TMPR.write( OpAddr2, DS, M2, R2 );
                    break;
                // subtraction
                case iSUB:
                    TMPS.fill( OpAddr1, DS, M1, R1 );
                    TMPD.fill( OpAddr2, DS, M2, R2 );
                    TMPR.subtract( TMPD, TMPS );
                    logger.info("TMPR(" + TMPR.hex() + ") = TMPD(" + TMPD.hex() + ") - TMPS(" + TMPS.hex() + ")");
                    setZN( TMPR );
                    setSmDmRm( TMPS, TMPD, TMPR );
                    V = ( !Sm & Dm & !Rm ) | ( Sm & !Dm & Rm );
                    C = ( Sm & !Dm ) | ( Rm & !Dm ) | ( Sm & Rm );
                    TMPR.write( OpAddr2, DS, M2, R2 );
                    break;
                // sub quick
                case iSUBQ:
                    TMPD.fill( OpAddr2, DS, M2, R2 );
                    TMPS.set( 0 );
                    TMPS.set( setByte(TMPS.get(), TwoBits.byte0, opcData) );
                    // Sign extension if W or L ??
                    TMPR.subtract( TMPD, TMPS );
                    logger.info("TMPR(" + TMPR.hex() + ") = TMPD(" + TMPD.hex() + ") - TMPS(" + TMPS.hex() + ")");
                    setZN( TMPR );
                    setSmDmRm( TMPS, TMPD, TMPR );
                    V = ( !Sm & Dm & !Rm ) | ( Sm & !Dm & Rm );
                    C = ( Sm & !Dm ) | ( Rm & !Dm ) | ( Sm & Rm );
                    TMPR.write( OpAddr2, DS, M2, R2 );
                    break;
                // signed multiplication
                case iMULS:
                    if( checkCond( (DS == DataSize.WordSize ), "Invalid Data Size" ) ) {
                        TMPS.fill( OpAddr1, DS, M1, R1 );
                        TMPD.fill( OpAddr2, DS, M2, R2 );
                        if( getBits( (short)TMPS.get(), 15, 15) == 1 )
                            TMPS.set( TMPS.get() | 0xFFFF0000 );
                        if( getBits( (short)TMPD.get(), 15, 15) == 1 )
                            TMPD.set( TMPD.get() | 0xFFFF0000 );
                        TMPR.multiply( TMPD, TMPS );
                        logger.info("TMPR(" + TMPR.hex() + ") = TMPD(" + TMPD.hex() + ") * TMPS(" + TMPS.hex() + ")");
                        setZN( TMPR );
                        V = false;
                        C = false;
                        TMPR.write( OpAddr2, DataSize.LongSize, M2, R2 );
                    }
                    break;
                // signed division
                case iDIVS:
                    boolean flag = false ;
                    if( checkCond( (DS == DataSize.LongSize ), "Invalid Data Size" ) ) {
                        TMPS.fill( OpAddr1, DataSize.WordSize, M1, R1);
                        logger.info("TMPS = " + TMPS.hex() );
                        if( checkCond( (TMPS.get() != 0), "Division by Zero" ) ) {
                            TMPD.fill( OpAddr2, DS, M2, R2 );
                            logger.info("TMPD = " + TMPD.hex());
                            V = ( (TMPD.get() / TMPS.get()) < -32768 ) | ( (TMPD.get() / TMPS.get()) > 32767 );
                            if( TMPS.get() > 0x8000 ) {
                                flag = true;
                                TMPS.set( (TMPS.get() ^ 0xFFFF) + 1 );
                                TMPD.set( ~(TMPD.get()) + 1 );
                            }
                            logger.info("TMPS = " + TMPS.hex() + "; TMPD = " + TMPD.hex());
                            if( ((TMPD.get() / TMPS.get()) == 0)  &&  flag ) {
                                int ss1 = setShort( TMPR.get(), LEAST, (short)0 );
                                TMPD.set( ~(TMPD.get()) + 1 );
                                logger.fine("TMPD = " + TMPD.hex() );
                                int ss2 = setShort( ss1, MOST, (short)(TMPD.get() % TMPS.get()) );
                                TMPR.set( ss2 );
                            }
                            else {
                                int wtmps_least = getWord( TMPS.get(), LEAST );
                                int tmpd_div_l = TMPD.get() / wtmps_least ;
                                int tmpd_mod_l = TMPD.get() % wtmps_least ;
                                int ss3 = setShort( tmpd_div_l, MOST, (short)tmpd_mod_l );
                                TMPR.set( ss3 );
                            }
                            logger.info("TMPR(" + TMPR.hex() + ") = TMPD(" + TMPD.hex() + ") / TMPS(" + TMPS.hex() + ")");
                            setZN( TMPR );
                            C = false ;
                            TMPR.write( OpAddr2, DS, M2, R2 );
                        }
                    }
                    break;
                // negate
                case iNEG:
                    TMPD.fill( OpAddr1, DS, M1, R1 );
                    TMPR.set( TMPD.get() * -1 );
                    setZN( TMPR );
                    setSmDmRm( TMPS, TMPD, TMPR );
                    V = Dm & Rm ;
                    C = Dm | Rm ;
                    TMPR.write( OpAddr1, DS, M1, R1 );
                    break;
                // clear
                case iCLR:
                    TMPD.set( 0 );
                    setZN( TMPD );
                    V = false;
                    C = false;
                    TMPD.write( OpAddr1, DS, M1, R1 );
                    break;
                // bitwise
                case iNOT:
                    TMPD.fill( OpAddr1, DS, M1, R1 );
                    TMPR.set( ~( TMPD.get()) );
                    setZN( TMPR );
                    V = false;
                    C = false;
                    TMPR.write( OpAddr1, DS, M1, R1 );
                    break;
                // bitwise
                case iAND:
                    TMPS.fill( OpAddr1, DS, M1, R1 );
                    TMPD.fill( OpAddr2, DS, M2, R2 );
                    TMPR.set( TMPD.get() & TMPS.get() );
                    logger.fine("TMPR(" + TMPR.hex() + ") = TMPD(" + TMPD.hex() + ") & TMPS(" + TMPS.hex() + ")");
                    setZN( TMPR );
                    V = false;
                    C = false;
                    TMPR.write( OpAddr2, DS, M2, R2 );
                    break;
                // bitwise
                case iOR:
                    TMPS.fill( OpAddr1, DS, M1, R1 );
                    TMPD.fill( OpAddr2, DS, M2, R2 );
                    TMPR.set( TMPD.get() | TMPS.get() );
                    logger.fine("TMPR(" + TMPR.hex() + ") = TMPD(" + TMPD.hex() + ") | TMPS(" + TMPS.hex() + ")");
                    setZN( TMPR );
                    V = false;
                    C = false;
                    TMPR.write( OpAddr2, DS, M2, R2 );
                    break;
                // xor
                case iEOR:
                    TMPS.fill( OpAddr1, DS, M1, R1 );
                    TMPD.fill( OpAddr2, DS, M2, R2 );
                    TMPR.set( TMPD.get() ^ TMPS.get() );
                    logger.fine("TMPR(" + TMPR.hex() + ") = TMPD(" + TMPD.hex() + ") ^ TMPS(" + TMPS.hex() + ")");
                    setZN( TMPR );
                    V = false;
                    C = false;
                    TMPR.write( OpAddr2, DS, M2, R2 );
                    break;
                // shift left
                case iLSL:
                    logger.fine( "iLSL: opcData = " + opcData );
                    TMPD.fill( OpAddr2, DS, M2, R2 );
                    logger.fine( "TMPD = " + intHex(TMPD.get()) );
                    TMPR.set( TMPD.get() << opcData );
                    logger.fine( "TMPR = " + intHex(TMPR.get()) );
                    setZN( TMPR );
                    V = false;
                    C = false;
                    if( opcData > 0 )
                        C = getBits( (short)TMPD.get(), (DS.sizeValue()*8 - opcData), (DS.sizeValue()*8 - opcData) ) == 1;
                    TMPR.write( OpAddr2, DS, M2, R2 );
                    break;
                // shift right
                case iLSR:
                    logger.fine( "iLSR: opcData = " + opcData );
                    TMPD.fill( OpAddr2, DS, M2, R2 );
                    logger.fine( "TMPD = " + intHex(TMPD.get()) );
                    TMPR.set( TMPD.get() >>> opcData );
                    logger.fine( "TMPR = " + intHex(TMPR.get()) );
                    setZN( TMPR );
                    V = false;
                    C = (opcData > 0) && getBits( (short)TMPD.get(), opcData-1, opcData-1 ) == 1 ;
                    TMPR.write( OpAddr2, DS, M2, R2 );
                    break;
                // rotate left
                case iROL:
                    logger.fine( "iROL: opcData = " + opcData );
                    TMPD.fill( OpAddr2, DS, M2, R2 );
                    logger.fine( "TMPD = " + intHex(TMPD.get()) );
                    opcData = (byte)( opcData % (8 * DS.sizeValue()) );
                    logger.fine( "iROL: opcData = " + opcData );
                    TMPR.set( TMPD.get() << opcData );
                    logger.fine( "TMPR = " + intHex(TMPR.get()) );
                    TMPS.set( TMPD.get() >>> ((8*DS.sizeValue()) - opcData) );
                    logger.fine( "TMPS = " + intHex(TMPS.get()) );
                    TMPR.set( setBits(TMPR.get(), (byte)0, (byte)(opcData-1), (short)TMPS.get()) );
                    logger.info( "TMPR = " + intHex(TMPR.get()) );
                    setZN( TMPR );
                    V = false;
                    C = (opcData > 0)
                        && getBits( (short)TMPD.get(), (DS.sizeValue()*8)-opcData, (DS.sizeValue()*8)-opcData ) == 1 ;
                    TMPR.write( OpAddr2, DS, M2, R2 );
                    break;
                // rotate right
                case iROR:
                    logger.fine( "iROR: opcData = " + opcData );
                    TMPD.fill( OpAddr2, DS, M2, R2 );
                    logger.fine( "TMPD = " + intHex(TMPD.get()) );
                    opcData = (byte)( opcData % (8*DS.sizeValue()) );
                    logger.fine( "iROR: opcData = " + opcData );
                    TMPR.set( TMPD.get() >>> opcData );
                    logger.fine( "TMPR = " + intHex(TMPR.get()) );
                    TMPR.set( setBits( TMPR.get(), (byte)(8*DS.sizeValue()-opcData),
                                       (byte)(8*DS.sizeValue()-1), (short)TMPD.get() ) );
                    logger.info( "now TMPR = " + intHex(TMPR.get()) );
                    setZN( TMPR );
                    V = false;
                    C = (opcData > 0) && ( getBits((short) TMPD.get(), opcData-1, opcData-1) == 1 );
                    TMPR.write( OpAddr2, DS, M2, R2 );
                    break;
                // compare
                case iCMP:
                    TMPS.fill( OpAddr1, DS, M1, R1 );
                    TMPD.fill( OpAddr2, DS, M2, R2 );
                    TMPR.subtract( TMPD, TMPS );
                    setZN( TMPR );
                    setSmDmRm( TMPS, TMPD, TMPR );
                    V = ( !Sm & Dm & !Rm ) | ( Sm & !Dm & Rm );
                    C = ( Sm & !Dm ) | ( Rm & !Dm ) | ( Sm & Rm );
                    break;
                // test
                case iTST:
                    TMPD.fill( OpAddr1, DS, M1, R1 );
                    setZN( TMPD );
                    V = false ;
                    C = false ;
                    break;
                // branch
                case iBRA:
                    if( checkCond( (M1 == AddressMode.RELATIVE_ABSOLUTE), "Invalid Addressing Mode" )
                            && checkCond( (DS == DataSize.WordSize ), "Invalid Data Size" ) )
                        PC = OpAddr1 ;
                    break;
                // branch if overflow
                case iBVS:
                    if( checkCond( (M1 == AddressMode.RELATIVE_ABSOLUTE), "Invalid Addressing Mode" )
                            && checkCond( (DS == DataSize.WordSize ), "Invalid Data Size" ) )
                        if( V ) PC = OpAddr1 ;
                    break;
                // branch if equal
                case iBEQ:
                    if( checkCond( (M1 == AddressMode.RELATIVE_ABSOLUTE), "Invalid Addressing Mode" )
                            && checkCond( (DS == DataSize.WordSize ), "Invalid Data Size" ) )
                        if( Z ) PC = OpAddr1 ;
                    break;
                // branch if carry
                case iBCS:
                    if( checkCond( (M1 == AddressMode.RELATIVE_ABSOLUTE), "Invalid Addressing Mode" )
                            && checkCond( (DS == DataSize.WordSize ), "Invalid Data Size" ) )
                        if( C ) PC = OpAddr1 ;
                    break;
                // branch if GTE
                case iBGE:
                    if( checkCond( (M1 == AddressMode.RELATIVE_ABSOLUTE), "Invalid Addressing Mode" )
                            && checkCond( (DS == DataSize.WordSize ), "Invalid Data Size" ) )
                        if( N == V ) PC = OpAddr1 ;
                    break;
                // branch if LTE
                case iBLE:
                    if( checkCond( (M1 == AddressMode.RELATIVE_ABSOLUTE), "Invalid Addressing Mode" )
                            && checkCond( (DS == DataSize.WordSize ), "Invalid Data Size" ) )
                        if( (N^V) ) PC = OpAddr1 ;
                    break;
                // move
                case iMOV:
                    TMPS.fill( OpAddr1, DS, M1, R1 );
                    TMPS.write( OpAddr2, DS, M2, R2 );
                    break;
                // move quick
                case iMOVQ:
                    TMPD.fill( OpAddr2, DS, M2, R2 );
                    TMPD.set( setByte( TMPD.get(), TwoBits.byte0, opcData) );
                    // Sign extension if W or L ??
                    setZN( TMPD );
                    V = false;
                    C = false;
                    TMPD.write( OpAddr2, DS, M2, R2 );
                    break;
                // exchange
                case iEXG:
                    if( checkCond( ((M1 == AddressMode.ADDRESS_REGISTER_DIRECT || M1 == AddressMode.DATA_REGISTER_DIRECT)
                                    && (M2 == AddressMode.ADDRESS_REGISTER_DIRECT || M2 == AddressMode.DATA_REGISTER_DIRECT)),
                            "Invalid Addressing Mode" ) ) {
                        TMPS.fill( OpAddr1, DS, M1, R1 );
                        TMPD.fill( OpAddr2, DS, M2, R2 );
                        TMPS.write( OpAddr1, DS, M2, R2 );
                        TMPD.write( OpAddr2, DS, M1, R1 );
                        V = false;
                        C = false;
                    }
                    break;
                // move to address
                case iMOVA:
                    if( checkCond( ((M1 == AddressMode.RELATIVE_ABSOLUTE) && (M2 == AddressMode.ADDRESS_REGISTER_DIRECT)),
                            "Invalid Addressing Mode" )
                            && checkCond( (DS == DataSize.WordSize ), "Invalid Data Size" ) )
                        //setResult( OpAddr1, OpAddr2, DS, M2, R2 );
                        AR[R2] = (short)getWord( OpAddr1, LEAST );
                    break;
                // input
                case iINP:
                    System.out.print("Enter a value ");
                    switch (DS) {
                        case ByteSize -> System.out.print( "(" + DataSize.ByteSize.strValue() + ") for " );
                        case WordSize -> System.out.print( "(" + DataSize.WordSize.strValue() + ") for " );
                        case LongSize -> System.out.print( "(" + DataSize.LongSize.strValue() + ") for " );
                        default -> {
                            logger.logError("INVALID data size '" + DS + "' for instruction '" + OpId + "' at PC = " + (PC-2));
                            H = true;
                            return;
                        }
                    }
                    switch (M1) {
                        case DATA_REGISTER_DIRECT -> System.out.print( "the register D" + R1 );
                        case ADDRESS_REGISTER_DIRECT -> System.out.print( "the register A" + R1 );
                        case ADDRESS_REGISTER_INDIRECT, ADDRESS_REGISTER_INDIRECT_PREDEC, ADDRESS_REGISTER_INDIRECT_POSTINC ->
                                System.out.print( "the memory address " + AR[R1] );
                        case RELATIVE_ABSOLUTE -> System.out.print( "the memory address " + OpAddr1 );
                        default -> {
                            logger.logError("INVALID mode type '" + M1 + "' for instruction '" + OpId + "' at PC = " + (PC-2));
                            H = true;
                            return;
                        }
                    }
                    System.out.print(": ");
                    String inpStr;
                    int radix = 10;
                    long inpl;
                    try {
                        Scanner inpScanner = new Scanner( System.in );
                        inpStr = inpScanner.next();
                        logger.info("input = " + inpStr);
                        if( inpStr.charAt(0) == HEX_MARKER ) {
                            radix = 16 ;
                            inpStr = inpStr.substring(1) ;
                        }
                        // Java only has signed numbers, so need a long to accept numbers like 0xFFFFFFFF
                        inpl = Long.parseLong( inpStr, radix );
                        logger.info("Long.parseLong(" + inpStr + ", " + radix + ") = " + inpl);
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                        return ;
                    }
                    TMPD.set( (int)inpl );
                    logger.info("TMPD = " + TMPD.hex() );
                    setZN( TMPD );
                    C = false;
                    V = false;
                    TMPD.write( OpAddr1, DS, M1, R1 );
                    break;
                // display
                case iDSP:
                    TMPS.fill( OpAddr1, DS, M1, R1 );
                    switch (M1) {
                        case DATA_REGISTER_DIRECT -> System.out.print( "[ D" + (int)R1 + " ]  = " );
                        case ADDRESS_REGISTER_DIRECT -> System.out.print( "[ A" + (int)R1 + " ]  = " );
                        case ADDRESS_REGISTER_INDIRECT -> System.out.print( "[" + intHex(AR[R1]) + " ] = " );
                        case ADDRESS_REGISTER_INDIRECT_POSTINC ->
                                // numBytes(DS) subtracted to compensate post-incrementation
                                System.out.print( "[" + intHex( AR[R1] - DS.sizeValue() ) + " ] = " );
                        case ADDRESS_REGISTER_INDIRECT_PREDEC -> System.out.print( "[" + intHex(AR[R1]) + "] = ");
                        case RELATIVE_ABSOLUTE -> System.out.print( "[" + intHex(OpAddr1) + "] = " );
                        default -> {
                            logger.logError( "\n\t>>> INVALID address mode '" + M1
                                                + "' for instruction '" + Mnemo[OpId] + "' at PC = " + (PC-2) );
                            H = true;
                            return;
                        }
                    }
                    switch (DS) {
                        case ByteSize -> System.out.println( intHex(TMPS.get() & 0xff)
                                                                + " (" + DataSize.ByteSize.strValue() + ")" );
                        case WordSize -> System.out.println( intHex(TMPS.get() & 0xffff)
                                                                + " (" + DataSize.WordSize.strValue() + ")" );
                        case LongSize -> System.out.println( intHex(TMPS.get()) + " (" + DataSize.LongSize.strValue() + ")" );
                        default -> {
                            logger.logError( "\n\t>>> INVALID data size '" + DS
                                             + "' for instruction '" + Mnemo[OpId] + "' at PC = " + (PC-2) );
                            H = true;
                            return;
                        }
                    }
                    break;
                // display status register
                case iDSR:
                    System.out.println("Status Bits: H:" + H + " N:" + N + " Z:" + Z + " V:" + V + " C:" + C);
                    break;
                // halt
                case iHLT:
                    H = true;
                    break;
                default:
                    logger.logError( "\n>>> ExecInstr() received invalid instruction '" + Mnemo[OpId]
                                     + "' at PC = " + (PC-2) );
                    H = true ;
            }
        }

        /** Determines the format of the instruction: return True if F1, False if F2 */
        boolean formatF1( byte opid ) {
            return (opid != iADDQ) && (opid != iSUBQ) && ( (opid < iLSL) || (opid > iROR) ) && (opid != iMOVQ);
        }
    }

    /** simulates a CPU */
    class Processor {
        Processor() {
            logger.logInit();
            DR = new int[2] ;
            AR = new short[2] ;
            ctrl = new Controller();
            MnemoInit();
        }

        private final Controller ctrl;

        /** Read into memory a machine language program contained in a file */
        boolean loadProgram( String fname ) {
            boolean inComment = false ;
            short address = 0 ;
            String input ;
            String inputFolder = "/newdata/dev/IntelliJIDEAProjects/Java/Sim68k/in/" ;
            String filename = inputFolder + fname ;
            try
            {
                File pf = new File( filename );
                Scanner fileScanner = new Scanner( pf );
                logger.info( "Processing file: " + filename );

                // get lines from the file
                while( fileScanner.hasNextLine() ) {
                    String nxtline = fileScanner.nextLine();
                    Scanner wordScanner = new Scanner( nxtline );
                    // get words from the line
                    while ( wordScanner.hasNext() ) {
                        input = wordScanner.next();
                        logger.fine("Read word: " + input);

                        // beginning & end of comment sections
                        if( input.equals(COMMENT_MARKER) ) {
                            inComment = ! inComment ;
                            continue ;
                        }
                        // skip comment
                        if( inComment )
                            continue ;

                        // process hex input
                        if( input.charAt(0) == HEX_MARKER ) {
                            String hx = input.substring(1, 3).toUpperCase( Locale.ROOT );
                            long decVal = Integer.parseInt( hx, 16 );
                            logger.info( "Read value '" + HEX_MARKER + hx + "(" + decVal + ")' into memory at location: "
                                         + address );
                            mem.load( address, (byte)decVal );
                            address++ ;
                        }
                    }
                    wordScanner.close();
                }
                fileScanner.close();
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }

            System.out.println("Program loaded. " + address + " bytes in memory.");
            return true ;
        }

        /** Initialize Mnemo with Strings corresponding to each instruction */
        void MnemoInit() {
            Mnemo = new String[iHLT + 1 ];
            Mnemo[iADD]   = "ADD";
            Mnemo[iADDQ]  = "ADDQ";
            Mnemo[iSUB]   = "SUB";
            Mnemo[iSUBQ]  = "SUBQ";
            Mnemo[iMULS]  = "MULS";
            Mnemo[iDIVS]  = "DIVS";
            Mnemo[iNEG]   = "NEG";
            Mnemo[iCLR]   = "CLR";
            Mnemo[iNOT]   = "NOT";
            Mnemo[iAND]   = "AND";
            Mnemo[iOR]    = "OR";
            Mnemo[iEOR]   = "EOR";
            Mnemo[iLSL]   = "LSL";
            Mnemo[iLSR]   = "LSR";
            Mnemo[iROL]   = "ROL";
            Mnemo[iROR]   = "ROR";
            Mnemo[iCMP]   = "CMP";
            Mnemo[iTST]   = "TST";
            Mnemo[iBRA]   = "BRA";
            Mnemo[iBVS]   = "BVS";
            Mnemo[iBEQ]   = "BEQ";
            Mnemo[iBCS]   = "BCS";
            Mnemo[iBGE]   = "BGE";
            Mnemo[iBLE]   = "BLE";
            Mnemo[iMOV]   = "MOVE";
            Mnemo[iMOVQ]  = "MOVEQ";
            Mnemo[iEXG]   = "EXG";
            Mnemo[iMOVA]  = "MOVEA";
            Mnemo[iINP]   = "INP";
            Mnemo[iDSP]   = "DSP";
            Mnemo[iDSR]   = "DSR";
            Mnemo[iHLT]   = "HLT";
        }

        /** Fetch-Execute Cycle simulated */
        void start() {
            logger.info( "\n\t>>> START PROGRAM >>>" );
            PC = 0;
            H = false;
            do // Repeat the Fetch-Execute Cycle until the Halt bit becomes true
            {
                ctrl.fetchOpCode();
                ctrl.decodeInstr();
                ctrl.fetchOperands();
                if( !H )
                    ctrl.execInstr();
            }
            while( !H );

            logger.info("\tEnd of Fetch-Execute Cycle");
        }
    }

    /*
     *  MAIN
     ********************************************************************************************* */
    public static void main(final String[] args) {
        System.out.println( "PROGRAM STARTED ON " + Thread.currentThread() );
        String logLevel = args.length > 0 ? args[0] : LogControl.DEFAULT_CONSOLE_LEVEL.getName();
        new Sim68k().startup(logLevel);
        System.out.println ("\nEnd of program Execution.");
        System.exit( 0 );
    }

    /** interact with the user and start the program */
    void startup(String logLevel) {
        // init logging
        logControl = new LogControl(logLevel);
        logger = logControl.getLogger();
        logger.config( Sim68k.class.getSimpleName() + " Log Level = " + logLevel );

        mem = new Memory();
        Processor proc = new Processor();
        String input = "0";
        // main menu
        try( Scanner inScanner = new Scanner(System.in) ) {
            while( !input.equals(QUIT) ) {
                System.out.print( "Your Option ('" + EXECUTE + "' to execute a program, '" + QUIT + "' to quit): " );
                // read the next word
                input = inScanner.next().toLowerCase();
                logger.info( "option input = " + input );
                switch( input ) {
                    case EXECUTE -> {
                        // execution on the simulator
                        System.out.print( "Name of the 68k binary program ('.68b' will be added automatically): " );
                        input = inScanner.next();
                        logger.info( "program input = " + input );
                        if( proc.loadProgram( input + ".68b" ) )
                            proc.start();
                        else
                            logger.logError( "PROBLEM loading File '" + input + ".68b'!" );
                    }
                    case TEST -> {
                        // info on system data sizes
                        System.out.println( "sizeof( byte ) == " + Byte.BYTES );
                        System.out.println( "sizeof( char ) == " + Character.BYTES );
                        System.out.println( "sizeof( short ) == " + Short.BYTES );
                        System.out.println( "sizeof( int ) == " + Integer.BYTES );
                        System.out.println( "sizeof( long ) == " + Long.BYTES );
                        int t = 0xFFFFFFFF;
                        System.out.println( "int t = 0xFFFFFFFF = " + t + " = " + intHexBin(t) );
                        String hexstr = "FFFFFFFF";
                        System.out.println( "String hexstr = " + hexstr );
                        long l = Long.parseLong( hexstr, 16 );
                        System.out.println( "Long.parseLong(" + hexstr + ") = " + l + " = " + Long.toHexString(l) );
                    }
                    case QUIT -> System.out.println( "Bye!" );
                    default -> System.out.println( "Invalid Option. Please enter '" + EXECUTE + "' or '" + QUIT + "'." );
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            logger.logError( "PROBLEM RUNNING PROGRAM!" );
            System.exit( 1339 );
        }
        logger.info("\tPROGRAM ENDED");
    }
}
