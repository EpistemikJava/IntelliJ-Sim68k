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

class Sim68k {
    /*
     *  DATA DEFINITIONS
     *=========================================================================================================*/

    final static String
        COMMENT_MARKER = "/" ,
        QUIT    = "q" ,
        EXECUTE = "e" ,
        TEST    = "t" ;

    final static char HEX_MARKER = '$' ;

    // List of OpCode | OpId
    final static int iADD =   0 ; // Regular binary integer addition
    final static int iADDQ =  1 ; // Quick binary integer addition
    final static int iSUB =   2 ; // Regular binary integer subtraction
    final static int iSUBQ =  3 ; // Quick binary integer subtraction
    final static int iMULS =  4 ; // Signed binary multiplication
    final static int iDIVS =  5 ; // Signed binary division
    final static int iNEG =   6 ; // Signed binary negation
    final static int iCLR =   7 ; // Clear (set to 0)
    final static int iNOT =   8 ; // Logical NOT
    final static int iAND =   9 ; // Logical AND
    final static int iOR  =  10 ; // Logical OR
    final static int iEOR =  11 ; // Logical EXCLUSIVE-OR
    final static int iLSL =  12 ; // Logical Shift Left
    final static int iLSR =  13 ; // Logical Shift Right
    final static int iROL =  14 ; // Rotate Left
    final static int iROR =  15 ; // Rotate Right
    final static int iCMP =  16 ; // Compare (to adjust CVNZH according to D-S)
    final static int iTST =  17 ; // Test    (to adjust CVNZH according to D)
    final static int iBRA =  18 ; // Unconditional branch to the given address
    final static int iBVS =  19 ; // Branch to the given address if overflow
    final static int iBEQ =  20 ; // Branch to the given address if equal
    final static int iBCS =  21 ; // Branch to the given address if carry
    final static int iBGE =  22 ; // Branch to the given address if greater | equal
    final static int iBLE =  23 ; // Branch to the given address if less | equal
    final static int iMOV =  24 ; // Regular move
    final static int iMOVQ = 25 ; // Quick move
    final static int iEXG =  26 ; // Exchange 2 registers
    final static int iMOVA = 27 ; // Move the given address into A[i]
    final static int iINP =  28 ; // Read from keyboard (input)
    final static int iDSP =  29 ; // Display the name, the source & the contents
    final static int iDSR =  30 ; // Display the contents of the Status booleans
    final static int iHLT =  31 ; // HALT

    final boolean LEAST = false ;
    final boolean MOST  = true ;
    final boolean WRITE = false ;
    final boolean READ  = true ;

    enum twobits { byte0, byte1, byte2, byte3 }

    enum DataSize {
        // 0x0..0xFF = 0x80..0x7F in 2's CF
        // NEEDS to be an unsigned char to work properly
        // typedef  unsigned char  byte ;
        byteSize((byte)1, "byte"),

        // 0x0..0xFFFF = 0x8000..0x7FFF in 2's CF
        // NEEDS to be an unsigned short to work properly
        // typedef  unsigned short  word ;
        wordSize((byte)2, "word"),

        // 0x0..0xFFFFFFFF = 0x80000000..0x7FFFFFFF in 2's CF
        // NEED a signed value for this type to work properly
        // use a typedef to ensure the int type we are using is signed
        // typedef  signed int  long_68k ;
        longSize((byte)4, "long");

        private final byte size;
        private final String name;

        DataSize(byte cd, String nom) {
            this.size = cd;
            this.name = nom;
        }

        byte sizeValue() { return size; }
        String strValue() { return name; }
    }
    static DataSize getDataSize(int code) {
        if( code == 0 ) return DataSize.byteSize ;
        if( code == 1 ) return DataSize.wordSize ;
        if( code == 2 ) return DataSize.longSize ;
        logger.logError( "INVALID DATA SIZE!" );
        return null ;
    }

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
    static AddressMode getAddressMode(int x) {
        if( x == 0 ) return AddressMode.DATA_REGISTER_DIRECT ;
        if( x == 1 ) return AddressMode.ADDRESS_REGISTER_DIRECT ;
        if( x == 2 ) {
            logger.warning( "UNUSED ADDRESS MODE: AM_TWO_UNUSED!" );
            return AddressMode.AM_TWO_UNUSED ;
        }
        if( x == 3 ) return AddressMode.RELATIVE_ABSOLUTE ;
        if( x == 4 ) return AddressMode.ADDRESS_REGISTER_INDIRECT ;
        if( x == 5 ) {
            logger.warning( "UNUSED ADDRESS MODE: AM_FIVE_UNUSED!" );
            return AddressMode.AM_FIVE_UNUSED ;
        }
        if( x == 6 ) return AddressMode.ADDRESS_REGISTER_INDIRECT_POSTINC ;
        if( x == 7 ) return AddressMode.ADDRESS_REGISTER_INDIRECT_PREDEC ;
        logger.logError( "INVALID ADDRESS MODE!" );
        return null ;
    }

    final int memorySize = 0x1001 ; // hex values 0x0000 to 0x1000

    static String[] Mnemo ; // Mnemonic String for opCodes

    /**
     * Logging management
     * @see LogControl
     */
    protected static LogControl logControl;

    /**
     * Logging actions
     * @see MhsLogger
     */
    protected static MhsLogger logger;

    // HARDWARE
    /////////////////////////////////////////////////////////////

    private Memory mem;

    // The CPU registers
    short PC ;               // Program Counter
    short OpCode ;           // OPCODE of the current instruction
    short OpAddr1, OpAddr2 ; // Operand Addresses

    // status bits
    boolean C ; // Carry
    boolean V ; // Overflow
    boolean Z ; // Zero
    boolean N ; // Negative
    boolean H ; // Halt

      int[] DR; // two Data Registers
    short[] AR; // two Address Registers

    short MAR ; // Memory Address Register
    int   MDR ; // Memory Data Register

    TempReg trDest, trSrc, trResult;  // Temporary Registers Dest, Src, Result

    /**
     ***************************************************************************

     Functions for bit manipulation.

     Pascal, unlike C, does not have operators that allow easy manipulation
     of bits within a byte or short. The following procedures and functions
     are designed to help you manipulate (extract and set) the bits.
     You may use or modify these procedures/functions or create others.

     ****************************************************************************/

    /* Returns a subString of bits between FirstBit and LastBit from wV
       Ex:
          Bit Positions:      1111 11
              (15..0)         5432 1098 7654 3210
          wV = 0x1234      =  0001 0010 0011 0100
          FirstBit = 3, LastBit = 9
          The bits from 3 to 9 are: 10 0011 0
          The function returns 0x0046 (0000 0000 0100 0110)  */
    short getBits( final short wV, final int FirstBit, final int LastBit) {
        return (short)( (wV >> FirstBit) & ((2 << (LastBit - FirstBit)) - 1) );
    }

    // Gets one word from nV
    // MSW: false = Least Significant Word, true = Most Significant Word
    short getWord( final int nV, boolean MSW) {
        if( MSW )
            return (short)( (nV & 0xFFFF0000) >> 16 );
        return (short)( nV & 0x0000FFFF );
    }

    // Sets the bit of pnV indicated by posn to val (false or true)
    int setBit(int pnV, final short posn, final boolean val) {
        byte bt = (val) ? (byte)1 : (byte)0 ;
        return (pnV & (0xFFFFFFFF - (1 << posn))) | (bt << posn) ;
    }

    // Sets the bits of pnV between first and last to the least significant bits of val
    int setBits(int pnV, final byte first, final byte last, final short val) {
        short pos;
        int result = pnV;
        for( pos = first; pos <= last; pos++ )
            result = setBit( result, pos, (getBits(val, (byte)(pos - first), (byte)(pos - first)) == 1) );
        return result;
    }

    // Sets one byte of pnV indicated by posn to val
    int setByte(int pnV, final twobits posn, final byte val) {
        switch (posn) {
            case byte0 -> { return (pnV & 0xFFFFFF00) | val; }
            case byte1 -> { return (pnV & 0xFFFF00FF) | (val << 8); }
            case byte2 -> { return (pnV & 0xFF00FFFF) | (val << 16); }
            case byte3 -> { return (pnV & 0x00FFFFFF) | (val << 24); }
        }
        return 0; // should NEVER reach here
    }

    // Sets one word of pnV indicated by MSW to val
    // MSW: false = Least Significant Word, true = Most Significant Word
    int setWord(int pnV, final boolean MSW, final short val) {
        if( MSW )
            return (pnV & 0x0000FFFF) | (val << 16) ;
        return (pnV & 0xFFFF0000) | val ;
    }

/**
 *  INNER CLASSES
 *=========================================================================================================*/

//    int TMPD, TMPS, TMPR ;  // Temporary Registers Dest, Src, Result
    class TempReg {
        TempReg() {
            logger.logInit();
        }
        private int value;

        void set(int val) { value = val; }
        int get() {return value; }

        void add(TempReg trA, TempReg trB) { set( trA.get() + trB.get() ); }
        void subtract(TempReg trA, TempReg trB) { set( trA.get() - trB.get() ); }
        void multiply(TempReg trA, TempReg trB) { set( trA.get() * trB.get() ); }

    /* ***************************************************************************
     Since many instructions will make local fetches between temporary registers
     (TMPS, TMPD, TMPR) & memory or the Dn & An registers it would be
     useful to create procedures to transfer the shorts/bytes between them.
     Here are 2 suggestions of procedures to do this.
     **************************************************************************** */

    void fill(
//            int*   pReg,     // tmp Register to modify - TMPS, TMPD or TMPR
            short       opAddrNo, // address of Operand (OpAddr1 | OpAddr2), for addressMode 3
            DataSize    dsz,      // Data Size
            AddressMode mode,     // required Addressing Mode
            byte        regNo   ) // Register number for A[n] or D[n]
        {
            logger.info( "" );
            switch (mode) {
                case DATA_REGISTER_DIRECT -> {
//                        *pReg = DR[regNo];
                    set( DR[regNo] );
                    if( dsz == DataSize.byteSize )
                        setByte( get(), twobits.byte1, (byte) 0 );
                    if( dsz.sizeValue() <= DataSize.wordSize.sizeValue() )
                        setWord( get(), MOST, (short) 0 );
                }
                case ADDRESS_REGISTER_DIRECT -> set( AR[regNo] );
                case RELATIVE_ABSOLUTE -> {
                    // We need to access memory, except for branching & MOVA.
                    MAR = opAddrNo;
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
                    AR[regNo] = (short) ( AR[regNo] + dsz.sizeValue() );
                }
                case ADDRESS_REGISTER_INDIRECT_PREDEC -> {
                    // We need to access memory.
                    AR[regNo] = (short) ( AR[regNo] - dsz.sizeValue() );
                    MAR = AR[regNo];
                    mem.access( dsz, READ );
                    set( MDR );
                }
                default -> { // This error should never occur, but just in case...!
                    logger.logError( "*** ERROR >> INVALID Addressing Mode '" + mode + "' at PC = " + ( PC - 2 ) );
                    H = true;
                }
            }
        }

    // Transfer the contents of temporary register to Register OR Memory
    void write(
//            int    tmpReg,   // Source Register (TMPD...)
            short       OpAddrNo, // Operand Address (OpAddr1...)
            DataSize    dsz,      // Data Size
            AddressMode mode,     // required Addressing Mode
            byte        RegNo )   // Register Number for A[n] or D[n]
    {
        logger.info( "" );
        switch( mode ) {
            case DATA_REGISTER_DIRECT:
                switch (dsz) {
                    case byteSize -> DR[RegNo] = setBits( DR[RegNo], (byte)0, (byte)7, (short)get() );
                    case wordSize -> DR[RegNo] = setWord( DR[RegNo], LEAST, getWord(get(), LEAST) );
                    case longSize -> DR[RegNo] = get();
                    default -> {
                        logger.logError( "*** ERROR >> INVALID data size '" + dsz + "' at PC = " + ( PC - 2 ) );
                        H = true;
                    }
                }
                break;

            case ADDRESS_REGISTER_DIRECT:
                AR[RegNo] = getWord( get(), LEAST );
                break;

            case RELATIVE_ABSOLUTE:
                // We need to access memory, except for branching & MOVA.
                MAR = OpAddrNo;
                MDR = get();
                mem.access( dsz, WRITE );
                break;

            case ADDRESS_REGISTER_INDIRECT:
                // We need to access memory
            case ADDRESS_REGISTER_INDIRECT_PREDEC:
                // ATTENTION: for some instructions, the address register has already been decremented by fillTmpReg
                // DO NOT decrement it a 2nd time here
                MAR = AR[RegNo];
                MDR = get();
                mem.access( dsz, WRITE );
                break;

            case ADDRESS_REGISTER_INDIRECT_POSTINC:
                // We need to access memory.
                // ATTENTION: for some instructions, the address register has already been incremented by fillTmpReg()
                // DO NOT increment it a 2nd time here
                MAR = (short) (AR[RegNo] - dsz.sizeValue());
                MDR = get();
                mem.access( dsz, WRITE );
                break;

            default: // invalid addressMode
                logger.logError("*** ERROR >> INVALID Addressing Mode '" + mode + "' at PC = " + (PC-2));
                H = true ;
        }
    }
}

    /// store information
    class Memory {
        Memory() {
            logger.logInit();
            memory = new byte[memorySize];
        }

        private final byte[] memory; // store the binary program

        // load the binary program to memory
        void load( short location, byte data ) {
            logger.info("Read value $" + data + " into memory at location: " + location);
            memory[location] = data ;
        }

        /*  Copies an element (Byte, Word, Long) from memory\CPU to CPU\memory.
         *  Verifies if we are trying to access an address outside the range allowed for addressing [0x0000..0x1000].
         *  Uses the RW (read|write) boolean.
         *  Parameter dsz determines the data size (byte, short, int/long).
         *  >> 2018-11-22: other parameters needed when put MAR, MDR, RW, H as Controller member variables
         */
        void access(DataSize dsz, boolean RW ) {
            if( MAR < memorySize ) { // valid Memory Address range
                if( RW ) { // Read = copy an element from memory to CPU
                    switch (dsz) {
                        case byteSize -> MDR = memory[MAR];
                        case wordSize -> MDR = memory[MAR] * 0x100 + memory[MAR + 1];
                        case longSize -> MDR = ( ( memory[MAR] * 0x1000000 ) & 0xFF000000 ) |
                                               ( ( memory[MAR + 1] * 0x10000 ) & 0x00FF0000 ) |
                                               ( ( memory[MAR + 2] * 0x100 ) & 0x0000FF00 ) |
                                               ( memory[MAR + 3] & 0x000000FF );
                        default -> {
                            logger.logError("*** ERROR >> INVALID data size: " + dsz.strValue());
                            H = true;
                        }
                    }
                    logger.config("READ of " + dsz.strValue() + ": MDR now has value $" + Integer.toHexString(MDR));
                }
                else { // RW false = Write = copy an element from the CPU to memory
                    switch (dsz) {
                        case byteSize -> {
                            memory[MAR] = (byte) ( MDR % 0x100 ); // LSB: 8 last bits
                            logger.info( dsz.strValue() + " WRITE: memory[" + MAR + "] now has value $" + memory[MAR] );
                        }
                        case wordSize -> {
                            memory[MAR] = (byte) ( ( MDR / 0x100 ) % 0x100 ); // MSB: 8 first bits
                            memory[MAR + 1] = (byte) ( MDR % 0x100 ); // LSB: 8 last bits
                            logger.info( dsz.strValue() + " WRITE: memory[" + MAR + "] now has value $" + memory[MAR]
                                    + "\t\t\tmemory[" + MAR + 1 + "] now has value $" + memory[MAR + 1] );
                        }
                        case longSize -> {
                            memory[MAR] = (byte) ( ( MDR >> 24 ) & 0x000000FF ); // MSB: 8 first bits
                            memory[MAR + 1] = (byte) ( ( MDR >> 16 ) & 0x000000FF );
                            memory[MAR + 2] = (byte) ( ( MDR >> 8 ) & 0x000000FF );
                            memory[MAR + 3] = (byte) ( MDR % 0x100 );
                            logger.info( dsz.strValue() + " WRITE: memory[" + MAR + "] now has value $" + memory[MAR]
                                        + "\t\t\tmemory[" + MAR + 1 + "] now has value $" + memory[MAR + 1]
                                        + "\t\t\tmemory[" + MAR + 2 + "] now has value $" + memory[MAR + 2]
                                        + "\t\t\tmemory[" + MAR + 2 + "] now has value $" + memory[MAR + 3] );
                        }
                        default -> {
                            logger.logError( "*** ERROR >> INVALID data size: " + dsz.strValue() );
                            H = true;
                        }
                    }
                }
            }
            else { // INVALID Memory Address
                logger.logError("*** ERROR >> INVALID address: " + MAR);
                H = true ; // End of simulation...!
            }
        }
    }

    // simulates fetch and execute
    class Controller {
        Controller() {
            trDest   = new TempReg();
            trSrc    = new TempReg();
            trResult = new TempReg(); // Temporary Registers Dest, Src, Result
        }

        DataSize DS ; // byte, short, long

        byte OpId ;    // numeric id for opCodes
        byte numOprd ; // number of necessary operands = opCode bit P + 1
        byte opcData ; // store data from opCode for Format F2

        // temp storage for Register # (from opCode) for operands 1 & 2
        byte R1, R2 ;
        // temp storage for AddressMode (from opCode) for operands 1 & 2
        AddressMode M1, M2 ;
        // Most Significant Bits of TMPS, TMPD, & TMPR
        boolean Sm, Dm, Rm ;

        /**
         *  Generic error verification function, with message display:
         *  if Cond is False, display an error message (including the OpName)
         *  The Halt bit will also be set if there is an Error.
         */
        boolean checkCond( boolean Cond, String Message ) {
            if( Cond )
                return true ;
            logger.logError("*** ERROR: '" + Message + "' at PC = " + (PC-2) + " for operation " + Mnemo[OpId], 1);
            H = true ; // program will halt
            return false ;
        }

        // Fetch the OpCode from memory
        void fetchOpCode() {
            logger.info("PC = " + PC);
            MAR = PC ;
            PC += 2 ;
            mem.access( DataSize.wordSize, READ );
            OpCode = getWord(MDR, LEAST); // get LSW from MDR
        }

        // Update the fields OpId, DS, numOprd, M1, R1, M2, R2 and opcData
        void decodeInstr() {
            DS = getDataSize( getBits(OpCode, 9, 10) );
            OpId = (byte)getBits( OpCode, 11, 15 );
            numOprd = (byte)( getBits(OpCode, 8, 8) + 1 );

            logger.config("OpCode $" + Integer.toHexString(OpCode) + " at PC = " + (PC-2)
                        + " : OpId = " + Mnemo[OpId] + ", size = " + DS.strValue() + ", numOprnd = " + numOprd);

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
                logger.logError("*** ERROR: INVALID number of operands '" + numOprd + "' at PC = " + (PC-2));
                H = true ;
            }
            logger.config("\t\t\t M1 = " + M1 + ", M2 = " + M2 + ", R1 = " + R1 + ", R2 = " + R2 + ", opcData = " + opcData);
        }

        // Fetch the operands, according to their number (numOprd) & addressing modes (M1 or M2)
        void fetchOperands() {
            logger.config(numOprd + ": at PC = " + (PC-2) + " : M1 = " + M1 + ", M2 = " + M2);

            // Fetch the address of 1st operand (in OpAddr1)
            if( formatF1(OpId) && (M1 == AddressMode.RELATIVE_ABSOLUTE) ) {
                MAR = PC ;
                mem.access( DataSize.wordSize, READ );
                OpAddr1 = getWord( MDR, LEAST ); // get LSW of MDR
                PC += 2 ;
            }

            // Fetch the address of 2nd operand, if F1 & 2 operands.
            // OR, operand of an instruction with format F2 put in OpAddr2
            if( M2 == AddressMode.RELATIVE_ABSOLUTE ) {
                MAR = PC ;
                mem.access( DataSize.wordSize, READ );
                OpAddr2 = getWord( MDR, LEAST ); // get LSW of MDR
                PC += 2 ;
            }

            // Check invalid number of operands.
            if( numOprd == 2  &&  !formatF1(OpId) ) {
                logger.logError("*** ERROR >> INVALID number of operands for " + Mnemo[OpId] + " at PC = " + (PC-2));
                H = true ;
            }
        }

        // set Status bits Z & N
        void setZN( TempReg tr ) {
            int trVal = tr.get();
            switch (DS) {
                case byteSize -> {
                    Z = getBits( getWord(trVal, LEAST), 0, 7 ) == 0;
                    N = getBits( getWord(trVal, LEAST), 7, 7 ) == 1;
                }
                case wordSize -> {
                    Z = ( getBits( getWord(trVal, LEAST), 0, 15 )  == 0 );
                    N = ( getBits( getWord(trVal, LEAST), 15, 15 ) == 1 );
                }
                case longSize -> {
                    Z = ( trVal == 0 );
                    N = ( getBits( getWord(trVal, MOST), 15, 15 ) == 1 );
                }
                default -> {
                    logger.logError( "*** ERROR >> INVALID data size '" + DS + "' at PC = " + ( PC - 2 ) );
                    H = true;
                }
            }
        }

        // The calculations for V & C are easier with these values
        void setSmDmRm( TempReg trS, TempReg trD, TempReg trR ) {
            byte mostSigBit = 15 ; // wordSize
            switch( DS ) {
                case byteSize: mostSigBit =  7 ; break;
                case wordSize: break;
                case longSize: mostSigBit = 31 ; break;
                default: logger.logError("*** ERROR >> INVALID data size '" + DS + "' at PC = " + (PC-2));
                         H = true ;
            }
            Sm = ( getBits( (short)trS.get(), mostSigBit, mostSigBit) == 1 );
            Dm = ( getBits( (short)trD.get(), mostSigBit, mostSigBit) == 1 );
            Rm = ( getBits( (short)trR.get(), mostSigBit, mostSigBit) == 1 );
        }

        /** The execution of each instruction is done via its micro-program */
        void execInstr() {
            byte i = 0 ; // counter
            logger.config("Controller::execInstr(" + Mnemo[OpId] + "." + DS.strValue()
                        + "): OpAd1 = " + OpAddr1 + ", OpAd2 = " + OpAddr2
                        + ", M1 = " + M1 + ", R1 = " + R1 + ", M2 = " + M2 + ", R2 = " + R2);

            /* Execute the instruction according to opCode
               Use a CASE structure where each case corresponds to an instruction & its micro-program  */
            switch( OpId ) {
                // addition
                case iADD:
                    /* EXAMPLE micro-program according to step 2.4.1 in section 3  */
                    // 1. Fill TMPS if necessary
                    trSrc.fill( OpAddr1, DS, M1, R1 );
                    // 2. Fill TMPD if necessary
                    trDest.fill( OpAddr2, DS, M2, R2 );
                    // 3. Compute TMPR using TMPS & TMPD
                    trResult.add( trSrc, trDest );
                    logger.info("TMPR($" + trResult + ") = TMPS($" + trSrc + ") + TMPD($" + trDest + ")");
                    // 4. Update status bits HZNVC if necessary
                    setZN( trResult );
                    setSmDmRm( trSrc, trDest, trResult );
                    V = ( Sm & Dm & !Rm ) | ( !Sm & !Dm & Rm );
                    C = ( Sm & Dm ) | ( !Rm & Dm ) | ( Sm & !Rm );
                    // 5. Store the result in the destination if necessary
                    trResult.write( OpAddr2, DS, M2, R2 );
                    break ;
                // add quick
                case iADDQ:
                    trDest.fill( OpAddr2, DS, M2, R2 );
                    trSrc.set( 0 );
                    trSrc.set( setByte(trSrc.get(), twobits.byte0, opcData) );
                    // Sign extension if W or L ??
                    trResult.add( trDest, trSrc );
                    logger.info("TMPR($" + trResult + ") = TMPS($" + trSrc + ") + TMPD($" + trDest + ")");
                    setZN( trResult );
                    setSmDmRm( trSrc, trDest, trResult );
                    V = ( Sm & Dm & !Rm ) | ( !Sm & !Dm & Rm );
                    C = ( Sm & Dm ) | ( !Rm & Dm ) | ( Sm & !Rm );
                    trResult.write( OpAddr2, DS, M2, R2 );
                    break;
                // subtraction
                case iSUB:
                    trSrc.fill( OpAddr1, DS, M1, R1 );
                    trDest.fill( OpAddr2, DS, M2, R2 );
                    trResult.subtract( trDest, trSrc );
                    logger.info("TMPR($" + trResult + ") = TMPD($" + trDest + ") - TMPS($" + trSrc + ")");
                    setZN( trResult );
                    setSmDmRm( trSrc, trDest, trResult );
                    V = ( !Sm & Dm & !Rm ) | ( Sm & !Dm & Rm );
                    C = ( Sm & !Dm ) | ( Rm & !Dm ) | ( Sm & Rm );
                    trResult.write( OpAddr2, DS, M2, R2 );
                    break;
                // sub quick
                case iSUBQ:
                    trDest.fill( OpAddr2, DS, M2, R2 );
                    trSrc.set( 0 );
                    trSrc.set( setByte(trSrc.get(), twobits.byte0, opcData) );
                    // Sign extension if W or L ??
                    trResult.subtract( trDest, trSrc );
                    logger.info("TMPR($" + trResult + ") = TMPD($" + trDest + ") - TMPS($" + trSrc + ")");
                    setZN( trResult );
                    setSmDmRm( trSrc, trDest, trResult );
                    V = ( !Sm & Dm & !Rm ) | ( Sm & !Dm & Rm );
                    C = ( Sm & !Dm ) | ( Rm & !Dm ) | ( Sm & Rm );
                    trResult.write( OpAddr2, DS, M2, R2 );
                    break;
                // signed multiplication
                case iMULS:
                    if( checkCond( (DS == DataSize.wordSize), "Invalid Data Size" ) ) {
                        trSrc.fill( OpAddr1, DS, M1, R1 );
                        trDest.fill( OpAddr2, DS, M2, R2 );
                        if( getBits( (short) trSrc.get(), 15, 15) == 1 )
                            trSrc.set( trSrc.get() | 0xFFFF0000 );
                        if( getBits( (short) trDest.get(), 15, 15) == 1 )
                            trDest.set( trDest.get() | 0xFFFF0000 );
                        trResult.multiply( trDest, trSrc );
                        logger.info("TMPR($" + trResult + ") = TMPD($" + trDest + ") * TMPS($" + trSrc + ")");
                        setZN( trResult );
                        V = false;
                        C = false;
                        trResult.write( OpAddr2, DataSize.longSize, M2, R2 );
                    }
                    break;
                // signed division
                case iDIVS:
                    if( checkCond( (DS == DataSize.longSize), "Invalid Data Size" ) ) {
                        trSrc.fill( OpAddr1, DataSize.wordSize, M1, R1);
                        logger.info("TMPS = $" + trSrc );
                        if( checkCond( ( trSrc.get() != 0), "Division by Zero" ) ) {
                            trDest.fill( OpAddr2, DS, M2, R2 );
                            logger.info("TMPD = $" + trDest );
                            V = ( ( trDest.get() / trSrc.get() ) < -32768 ) | ( ( trDest.get() / trSrc.get() ) > 32767 );
                            if( trSrc.get() > 0x8000 ) {
                                i = 1;
                                trSrc.set( (trSrc.get() ^ 0xFFFF) + 1 );
                                trDest.set( ~(trDest.get()) + 1 );
                            }
                            
                            logger.info("TMPS = $" + trSrc + "; TMPD = $" + trDest );
                            if( (( trDest.get() / trSrc.get() ) == 0) && (i == 1) ) {
                                trResult.set( setWord(trResult.get(), LEAST, (short)0) );
                                logger.info("TMPR = $" + trResult );
                                trDest.set( ~(trDest.get()) + 1 );
                                logger.info("TMPD = $" + trDest );
                                trResult.set( setWord(trResult.get(), MOST, (short)(trDest.get() % trSrc.get())) );
                            }
                            else {
                                trResult.set( trDest.get() / getWord(trSrc.get(), LEAST) );
                                logger.info("TMPR = $" + trResult );
                                trResult.set( setWord(trResult.get(), MOST, (short)(trDest.get() % getWord(trSrc.get(), LEAST))) );
                            }

                            logger.info("TMPR($" + trResult + ") = TMPD($" + trDest + ") / TMPS($" + trSrc + ")");
                            setZN( trResult );
                            C = false ;
                            trResult.write( OpAddr2, DS, M2, R2 );
                        }
                    }
                    break;
                // negate
                case iNEG:
                    trDest.fill( OpAddr1, DS, M1, R1 );
                    trResult.set( trDest.get() * -1 );
                    setZN( trResult );
                    setSmDmRm( trSrc, trDest, trResult );
                    V = Dm & Rm ;
                    C = Dm | Rm ;
                    trResult.write( OpAddr1, DS, M1, R1 );
                    break;
                // clear
                case iCLR:
                    trDest.set( 0 );
                    setZN( trDest );
                    V = false;
                    C = false;
                    trDest.write( OpAddr1, DS, M1, R1 );
                    break;
                // bitwise
                case iNOT:
                    trDest.fill( OpAddr1, DS, M1, R1 );
                    trResult.set( ~(trDest.get()) );
                    setZN( trResult );
                    V = false;
                    C = false;
                    trResult.write( OpAddr1, DS, M1, R1 );
                    break;
                // bitwise
                case iAND:
                    trSrc.fill( OpAddr1, DS, M1, R1 );
                    trDest.fill( OpAddr2, DS, M2, R2 );
                    trResult.set( trDest.get() & trSrc.get() );
                    logger.info("TMPR($" + trResult + ") = TMPD($" + trDest + ") & TMPS($" + trSrc + ")");
                    setZN( trResult );
                    V = false;
                    C = false;
                    trResult.write( OpAddr2, DS, M2, R2 );
                    break;
                // bitwise
                case iOR:
                    trSrc.fill( OpAddr1, DS, M1, R1 );
                    trDest.fill( OpAddr2, DS, M2, R2 );
                    trResult.set( trDest.get() | trSrc.get() );
                    logger.info("TMPR($" + trResult + ") = TMPD($" + trDest + ") | TMPS($" + trSrc + ")");
                    setZN( trResult );
                    V = false;
                    C = false;
                    trResult.write( OpAddr2, DS, M2, R2 );
                    break;
                // xor
                case iEOR:
                    trSrc.fill( OpAddr1, DS, M1, R1 );
                    trDest.fill( OpAddr2, DS, M2, R2 );
                    trResult.set( trDest.get() ^ trSrc.get() );
                    logger.info("TMPR($" + trResult + ") = TMPD($" + trDest + ") ^ TMPS($" + trSrc + ")");
                    setZN( trResult );
                    V = false;
                    C = false;
                    trResult.write( OpAddr2, DS, M2, R2 );
                    break;
                // shift left
                case iLSL:
                    trDest.fill( OpAddr2, DS, M2, R2 );
                    trResult.set( trDest.get() + opcData );
                    setZN( trResult );
                    V = false;
                    if( opcData > 0 )
                        C = getBits( (short)trDest.get(), DS.sizeValue() * 8 - opcData, DS.sizeValue() * 8 - opcData ) == 1;
                    else
                        C = false;
                    trResult.write( OpAddr2, DS, M2, R2 );
                    break;
                // shift right
                case iLSR:
                    /*
                     In Turbo Pascal, SHR did NOT sign-extend, whereas >> in C is "machine-dependent" and in this platform
                     (Linux x86_64) it DOES sign extension for variables, so DO NOT get the proper answer for some operations.
                     We want to ensure there is NEVER any sign extension, to duplicate the Pascal results,
                     HOWEVER C does NOT sign-extend CONSTANTS, so CANNOT correct for sign extension by doing this:
                     TMPR = TMPS & !( 0x80000000 >> (opcData-1) ) as the constant 0x80000000 is NOT extended!
                     Instead, need to put 0x80000000 in a register and proceed as below:
                     */
                    trDest.fill( OpAddr2, DS, M2, R2 );
                    logger.info("TMPD = $" + trDest + "; opcData = " + opcData);
                    trSrc.set( 0x80000000 );
                    trResult.set( trSrc.get() >> (opcData-1) );
                    logger.info("TMPS = $" + trSrc + "; TMPR = TMPS >> " + (opcData-1) + " = $" + trResult );
                    trSrc.set( ~(trResult.get()) );
                    logger.info("TMPS = !TMPR = $" + trSrc );
                    trResult.set( (trDest.get() >> opcData) & trSrc.get() );
                    setZN( trResult );
                    V = false;
                    C = (opcData > 0) && ( getBits( (short)trDest.get(), opcData - 1, opcData - 1 ) == 1 );
                    trResult.write( OpAddr2, DS, M2, R2 );
                    break;
                // rotate left
                case iROL:
                    trDest.fill( OpAddr2, DS, M2, R2 );
                    opcData = (byte) (opcData % (8 * DS.sizeValue() ));
                    trResult.set( trDest.get() + opcData );
                    trSrc.set( trDest.get() >> ((8*DS.sizeValue()) - opcData) );
                    trResult.set( setBits( trResult.get(), (byte)0, (byte)(opcData-1), (short)trSrc.get()) );
                    setZN( trResult );
                    V = false;
                    C = (opcData > 0)
                        && ( getBits( (short)trDest.get(), (DS.sizeValue() * 8)-opcData, (DS.sizeValue()*8)-opcData ) == 1 );
                    trResult.write( OpAddr2, DS, M2, R2 );
                    break;
                // rotate right
                case iROR:
                    trDest.fill( OpAddr2, DS, M2, R2 );
                    opcData = (byte) (opcData % ( 8*DS.sizeValue() ));
                    trResult.set( trDest.get() >> opcData );
                    trResult.set( setBits( trResult.get(), (byte)(8*DS.sizeValue()-opcData), (byte)(8*DS.sizeValue()-1), (short)trDest.get()) );
                    setZN( trResult );
                    V = false;
                    C = (opcData > 0) && ( getBits( (short)trDest.get(), opcData - 1, opcData - 1 ) == 1 );
                    trResult.write( OpAddr2, DS, M2, R2 );
                    break;
                // compare
                case iCMP:
                    trSrc.fill( OpAddr1, DS, M1, R1 );
                    trDest.fill( OpAddr2, DS, M2, R2 );
                    trResult.subtract( trDest, trSrc );
                    setZN( trResult );
                    setSmDmRm( trSrc, trDest, trResult );
                    V = ( !Sm & Dm & !Rm ) | ( Sm & !Dm & Rm );
                    C = ( Sm & !Dm ) | ( Rm & !Dm ) | ( Sm & Rm );
                    break;
                // test
                case iTST:
                    trDest.fill( OpAddr1, DS, M1, R1 );
                    setZN( trDest );
                    V = false ;
                    C = false ;
                    break;
                // branch
                case iBRA:
                    if( checkCond( (M1 == AddressMode.RELATIVE_ABSOLUTE), "Invalid Addressing Mode" )
                            && checkCond( (DS == DataSize.wordSize), "Invalid Data Size" ) )
                        PC = OpAddr1 ;
                    break;
                // branch if overflow
                case iBVS:
                    if( checkCond( (M1 == AddressMode.RELATIVE_ABSOLUTE), "Invalid Addressing Mode" )
                            && checkCond( (DS == DataSize.wordSize), "Invalid Data Size" ) )
                        if( V ) PC = OpAddr1 ;
                    break;
                // branch if equal
                case iBEQ:
                    if( checkCond( (M1 == AddressMode.RELATIVE_ABSOLUTE), "Invalid Addressing Mode" )
                            && checkCond( (DS == DataSize.wordSize), "Invalid Data Size" ) )
                        if( Z ) PC = OpAddr1 ;
                    break;
                // branch if carry
                case iBCS:
                    if( checkCond( (M1 == AddressMode.RELATIVE_ABSOLUTE), "Invalid Addressing Mode" )
                            && checkCond( (DS == DataSize.wordSize), "Invalid Data Size" ) )
                        if( C ) PC = OpAddr1 ;
                    break;
                // branch if GTE
                case iBGE:
                    if( checkCond( (M1 == AddressMode.RELATIVE_ABSOLUTE), "Invalid Addressing Mode" )
                            && checkCond( (DS == DataSize.wordSize), "Invalid Data Size" ) )
                        if( N == V ) PC = OpAddr1 ;
                    break;
                // branch if LTE
                case iBLE:
                    if( checkCond( (M1 == AddressMode.RELATIVE_ABSOLUTE), "Invalid Addressing Mode" )
                            && checkCond( (DS == DataSize.wordSize), "Invalid Data Size" ) )
                        if( (N^V) ) PC = OpAddr1 ;
                    break;
                // move
                case iMOV:
                    trSrc.fill( OpAddr1, DS, M1, R1 );
                    trSrc.write( OpAddr2, DS, M2, R2 );
                    break;
                // move quick
                case iMOVQ:
                    trDest.fill( OpAddr2, DS, M2, R2 );
                    trDest.set( setByte(trDest.get(), twobits.byte0, opcData) );
                    // Sign extension if W or L ??
                    setZN( trDest );
                    V = false;
                    C = false;
                    trDest.write( OpAddr2, DS, M2, R2 );
                    break;
                // exchange
                case iEXG:
                    if( checkCond( ((M1 == AddressMode.ADDRESS_REGISTER_DIRECT || M1 == AddressMode.DATA_REGISTER_DIRECT)
                                    && (M2 == AddressMode.ADDRESS_REGISTER_DIRECT || M2 == AddressMode.DATA_REGISTER_DIRECT)),
                            "Invalid Addressing Mode" ) ) {
                        trSrc.fill( OpAddr1, DS, M1, R1 );
                        trDest.fill( OpAddr2, DS, M2, R2 );
                        trSrc.write( OpAddr1, DS, M2, R2 );
                        trDest.write( OpAddr2, DS, M1, R1 );
                        V = false;
                        C = false;
                    }
                    break;
                // move to address
                case iMOVA:
                    if( checkCond( ((M1 == AddressMode.RELATIVE_ABSOLUTE) && (M2 == AddressMode.ADDRESS_REGISTER_DIRECT)),
                            "Invalid Addressing Mode" )
                            && checkCond( (DS == DataSize.wordSize), "Invalid Data Size" ) )
                        //setResult( OpAddr1, OpAddr2, DS, M2, R2 );
                        AR[R2] = getWord( OpAddr1, LEAST );
                    break;
                // input
                case iINP:
                    System.out.print("Enter a value ");
                    switch (DS) {
                        case byteSize -> System.out.print( "(" + DataSize.byteSize.sizeValue() + ") for " );
                        case wordSize -> System.out.print( "(" + DataSize.wordSize.sizeValue() + ") for " );
                        case longSize -> System.out.print( "(" + DataSize.longSize.sizeValue() + ") for " );
                        default -> {
                            logger.logError( "ERROR >> INVALID data size '" + DS + "' for instruction '" + OpId
                                            + "' at PC = " + ( PC - 2 ) );
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
                            logger.info( "ERROR >> INVALID mode type '" + M1 + "' for instruction '" + OpId
                                         + "' at PC = " + ( PC - 2 ) );
                            H = true;
                            return;
                        }
                    }
                    System.out.println(": ");
                    String inpStr = "0";
                    try {
                        Scanner inpScanner = new Scanner( System.in );
                        inpStr = inpScanner.next();
                        logger.info("input == $" + inpStr);
                        logger.info("Integer.getInteger(inpStr) == $" + Integer.getInteger("0x" + inpStr) );
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                    /*
                     * On Windows, where the size of long is 4 bytes, even on my Windows 10 x64 laptop,
                     * strtol() evaluates String "0xFFFFFFFF" to int 0x7FFFFFFF!!
                     * Use strtoull() to ensure the maximum size to store our input without problems interpreting sign.
                     * Seems to work, so far, for both Linux x86_64 and Windows x64...
                     */
                    trDest.set( Integer.parseInt(inpStr, 16) );
                    logger.info("TMPD == $" + trDest.get() );
                    setZN( trDest );
                    C = false;
                    V = false;
                    trDest.write( OpAddr1, DS, M1, R1 );
                    break;
                // display
                case iDSP:
                    trSrc.fill( OpAddr1, DS, M1, R1 );
                    switch (M1) {
                        case DATA_REGISTER_DIRECT -> System.out.print( "[ D" + (int) R1 + " ]  = " );
                        case ADDRESS_REGISTER_DIRECT -> System.out.print( "[ A" + (int) R1 + " ]  = " );
                        case ADDRESS_REGISTER_INDIRECT -> System.out.print( "[$" + AR[R1] + " ] = " );
                        case ADDRESS_REGISTER_INDIRECT_POSTINC -> // numBytes(DS) subtracted to compensate post-incrementation
                                System.out.print( "[$" + (AR[R1] - DS.sizeValue()) + " ] = " );
                        case ADDRESS_REGISTER_INDIRECT_PREDEC -> System.out.print( "[$" + AR[R1] + "] = ");
                        case RELATIVE_ABSOLUTE -> System.out.print( "[$" + OpAddr1 + "] = " );
                        default -> {
                            logger.logError( "\n*** ERROR >> INVALID address mode '" + M1
                                    + "' for instruction '" + Mnemo[OpId] + "' at PC = " + ( PC - 2 ) );
                            H = true;
                            return;
                        }
                    }
                    switch (DS) {
                        case byteSize -> System.out.println( "$" + Integer.toHexString(trSrc.get() & 0xff).toUpperCase()
                                            + " (" + DataSize.byteSize.strValue() + ")" );
                        case wordSize -> System.out.println( "$" + Integer.toHexString(trSrc.get() & 0xffff).toUpperCase()
                                            + " (" + DataSize.wordSize.strValue() + ")" );
                        case longSize -> System.out.println( "$" + Integer.toHexString(trSrc.get()).toUpperCase(Locale.ROOT)
                                            + " (" + DataSize.longSize.strValue() + ")" );
                        default -> {
                            logger.logError( "\n*** ERROR >> INVALID data size '" + DS
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
                    H = true; // Set the Halt bit to true (stops program)
                    break;
                default:
                    logger.logError("*** ERROR >> ExecInstr() received invalid instruction '" + Mnemo[OpId]
                                    + "' at PC = " + (PC-2));
                    H = true ;
            }
        }

        // Determines the format of the instruction: return True if F1, False if F2
        boolean formatF1( byte opid ) {
            return ( opid != iADDQ ) && ( opid != iSUBQ ) && ( ( opid < iLSL ) || ( opid > iROR ) ) && ( opid != iMOVQ );
        }
    }


    class Processor {
        Processor() {
            DR = new int[2] ;
            AR = new short[2] ;
            ctrl = new Controller();
            MnemoInit();
        }

        private final Controller ctrl;

        // Read into memory a machine language program contained in a file
        boolean loadProgram( String fname ) {
            boolean inComment = false ;
            short address = 0 ;
            String input ;
            long decVal ;
            String nxtline ;
            String inputFolder = "/newdata/dev/IntelliJIDEAProjects/Java/Sim68k/in/" ;
            String filename = inputFolder + fname ;
            try
            {
                File pf = new File( filename );
                Scanner fileScanner = new Scanner( pf );
                logger.info( "Processing file: " + filename );

                // get lines from the file
                while( fileScanner.hasNextLine() ) {
                    nxtline = fileScanner.nextLine();
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
                            String hx = input.substring( 1, 3 ).toUpperCase( Locale.ROOT );
                            decVal = Integer.parseInt( hx, 16 );
                            logger.info("Read value '" + HEX_MARKER + hx + "(" + decVal + ")' into memory at location: " + address);
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

//            writeln ('Program loaded. ', address,' bytes in memory.');
            System.out.println("Program loaded. " + address + " bytes in memory.");
            return true ;
        }

        // Initialize Mnemo with Strings corresponding to each instruction
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

        // Fetch-Execute Cycle simulated
        void start() {
            logger.info( "" );
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
     **************************************************************************/
    public static void main(final String[] args) {
        System.out.println( "PROGRAM STARTED ON " + Thread.currentThread() );
        String logLevel = args.length > 0 ? args[0] : LogControl.DEFAULT_CONSOLE_LEVEL.getName();
        new Sim68k().startup(logLevel);
        System.out.println ("\nEnd of program Execution.");
        System.exit( 0 );
    }

    void startup(String logLevel) {
        // init logging
        logControl = new LogControl(logLevel);
        logger = logControl.getLogger();
        logger.config( Sim68k.class.getSimpleName() + " Log Level = " + logLevel );

        mem = new Memory();
        Processor proc = new Processor();
        String input = "0";
        int t;
        String hexstr;
        Scanner inScanner = new Scanner(System.in);

        // Menu
        while( !input.equals(QUIT) ) {
            System.out.println("Your Option ('" + EXECUTE + "' to execute a program, '" + QUIT + "' to quit): ");
            // read the next word
            input = inScanner.next().toLowerCase();
            System.out.println("input = " + input);
            switch (input) {
                case EXECUTE -> {
                    // execution on the simulator
                    System.out.println( "Name of the 68k binary program ('.68b' will be added automatically): " );
                    input = inScanner.next();
                    System.out.println( "input = " + input );
                    if( proc.loadProgram( input + ".68b" ) )
                        proc.start();
                    else
                        logger.logError( "PROBLEM with loading File '" + input + ".68b'!" );
                }
                case TEST -> {
                    // info on system data sizes
                    System.out.println( "sizeof( byte ) == " + Byte.BYTES );
                    System.out.println( "sizeof( char ) == " + Character.BYTES );
                    System.out.println( "sizeof( short ) == " + Short.BYTES );
                    System.out.println( "sizeof( int ) == " + Integer.BYTES );
                    System.out.println( "sizeof( long ) == " + Long.BYTES );
                    t = 0xFFFFFFFF;
                    System.out.println( "int t = 0xFFFFFFFF = $" + t + " = " + Integer.toHexString( t ) );
                    hexstr = "FFFFFFFF";
                    System.out.println( "String hexstr = " + hexstr );
                    long l = Long.parseLong( hexstr, 16 );
                    System.out.println( "Long.parseLong(" + hexstr + ") = " + l + " = " + Long.toHexString( l ) );
                }
                case QUIT -> System.out.println( "Bye!" );
                default -> System.out.println( "Invalid Option. Please enter '" + EXECUTE + "' or '" + QUIT + "'." );
            }
        }
        inScanner.close();
        logger.info("\tPROGRAM ENDED");
    }
}
