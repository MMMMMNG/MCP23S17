package com.rrr.mcp23s17;

import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.spi.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;

import java.io.IOException;
import java.util.*;

/**
 * <p>
 * An interface for the MCP23S17 SPI IO expander for Raspberry Pi.
 * </p>
 * <p>
 * This class abstracts away the technical details of communicating with the chip via SPI, allowing for simple access
 * via {@link PinView PinView} objects. One {@code PinView} object exists per {@link Pin Pin}, and {@code PinView}
 * objects are initialized lazily. {@code PinView} references are returned by
 * {@link MCP23S17#getPinView(Pin) getPinView}.
 * </p>
 * <p>
 * It is critical to note that, to allow for writing to the MCP23S17's registers in batches, the setter methods on
 * {@code PinView}s (or, in general, the methods that modify state for that pin) do not actually perform a write to the
 * chip; they merely set program state. In order to write to the chip, each invocation of a state-changing
 * {@code PinView} method (or a batch thereof) must be followed by a call to the appropriate {@code writeXXX} method(s)
 * on the {@code MCP23S17} object.
 * </p>
 * <p>
 * To setup the chip to use interrupts, the interrupt {@linkplain DigitalInput input pins} must be passed in
 * during construction at the appropriate static factory method; one static factory method is provided for each possible
 * interrupt setup. Once a chip is setup for interrupts, callbacks for both
 * {@linkplain PinView#addListener(InterruptListener) pin-specific interrupts} and
 * {@linkplain MCP23S17#addGlobalListener(InterruptListener) global interrupts} may be registered. Do note, however,
 * that each pin that is supposed to generate interrupts must be setup as such in the appropriate registers. Refer to
 * the <a href=http://ww1.microchip.com/downloads/en/DeviceDoc/20001952C.pdf>MCP23S17 datasheet</a> for more
 * information.
 * </p>
 *
 * @author Robert Russell, updated to pi4j-v2 by MNG
 */
// TODO: that writeXXX must be called after a PinView setter method is extremely awkward. The same support for batch
//       writes might be facilitated (in a future version) by some locking/synchronization mechanism where a lock is
//       acquired, the changes are made, and then the lock is released, upon which the changes are actually written to
//       the chip; if the changes were aborted/an error occurred/whatever happens before the lock is released, all the
//       changes would simply be forgotten without (as is currently the case) local state potentially becoming
//       out-of-sync with the actual values in the registers on the chip. Maybe this is over-engineering it though...
public final class MCP23S17 {
    public static final int SPI_SPEED_HZ = 1000000;  // 1 MHz; Max 10 MHz
    public static final String EXC_AMOUNT_OUTSIDE_1_TO_8 = "amount [{}] must be between 1 and 8 as there can only be 8 addresses per Bus";
    public static final String EXC_HW_ADDR_OUTSIDE_0_TO_7 = "hardWareAddress [{}] must be between 0 and 7, as there are only 3 physical address pins on the MCP23S12 IC";
    public static final String EXC_AMOUNT_BIGGER_THAN_INTERRUPT_ARRAY = "amount [{}] must be smaller than or equal to the amount of provided interrupts [{}]";
    private static final Logger LOG = LoggerFactory.getLogger(MCP23S17.class);
    // Register addresses for IOCON.BANK = 0
    private static final byte ADDR_IODIRA = 0x00;
    private static final byte ADDR_IODIRB = 0x01;
    private static final byte ADDR_IPOLA = 0x02;
    private static final byte ADDR_IPOLB = 0x03;
    private static final byte ADDR_GPINTENA = 0x04;
    private static final byte ADDR_GPINTENB = 0x05;
    private static final byte ADDR_DEFVALA = 0x06;
    private static final byte ADDR_DEFVALB = 0x07;
    private static final byte ADDR_INTCONA = 0x08;
    private static final byte ADDR_INTCONB = 0x09;
    private static final byte ADDR_IOCON = 0x0A;
    private static final byte ADDR_GPPUA = 0x0C;
    private static final byte ADDR_GPPUB = 0x0D;
    private static final byte ADDR_INTFA = 0x0E;
    private static final byte ADDR_INTFB = 0x0F;
    private static final byte ADDR_INTCAPA = 0x10;
    private static final byte ADDR_INTCAPB = 0x11;
    private static final byte ADDR_GPIOA = 0x12;
    private static final byte ADDR_GPIOB = 0x13;
    private static final byte ADDR_OLATA = 0x14;
    private static final byte ADDR_OLATB = 0x15;
    // These arrays are used for interrupt handling. (Interrupts are port-specific, so having separate arrays for each
    // port means we can iterate over the pins belonging to either port A or B.)
    private static final Pin[] PORT_A_PINS =
            {Pin.PIN0, Pin.PIN1, Pin.PIN2, Pin.PIN3, Pin.PIN4, Pin.PIN5, Pin.PIN6, Pin.PIN7};
    private static final Pin[] PORT_B_PINS =
            {Pin.PIN8, Pin.PIN9, Pin.PIN10, Pin.PIN11, Pin.PIN12, Pin.PIN13, Pin.PIN14, Pin.PIN15};
    // OPCODES--these are written before a register address in the read and write processes.
    private static final byte WRITE_OPCODE = 0x40;
    private static final byte READ_OPCODE = 0x41;
    /**
     * The SPI communication interface.
     */
    private final Spi spi;
    /**
     * Stores whether this chip will read the GPIO registers to check for state change on the pins
     * or the INTCAP registers.
     */
    private final boolean readGPIORegisterOnInterrupt;
    /**
     * The lazily-instantiated {@link PinView PinView}s. This object is synchronized on in
     * {@link MCP23S17#getPinView(Pin) getPinView} as {@code PinView}s are accessed in the
     * {@linkplain MCP23S17#callInterruptListeners(byte, byte, Pin[]) interrupt handling routine} (which is invoked in a
     * different thread).
     */
    private final EnumMap<Pin, PinView> pinViews = new EnumMap<>(Pin.class);
    /**
     * The registered global listeners--not pin-specific listeners. This object is synchronized on so that listeners
     * cannot be added or removed while an interrupt is being processed or vice versa.
     */
    private final Collection<InterruptListener> globalListeners = new HashSet<>(0);
    // These are only referenced so they are not GCed.
    private final DigitalInput portAInterrupt;
    private final DigitalInput portBInterrupt;
    /**
     * This {@code Object}'s intrinsic lock is acquired whenever one of the above bytes is written to so that two
     * threads may not write to the same byte at the same time. Ideally we would have one lock per byte because, for
     * example, a thread writing only to IODIRB need not yield to a thread writing only to IODIRA, but then we would
     * have fourteen lock objects! Byte writes should be infrequent enough and fast enough for this to not be an issue,
     * however.
     */
    private final Object byteWriteLock = new Object();

    //these bytes are the WRITE_OPCODE and READ_OPCODE bitwise ORed with the HW address
    private final byte writeByte;
    private final byte readByte;
    // The bytes representing the value of the chip's various registers.
    // They are initialized to the corresponding registers' initial value, as given in the MCP23S17 datasheet.
    // Note that these bytes do not necessarily represent the actual value in the corresponding register (i.e. they may
    // be out of sync with the registers).
    private volatile byte IODIRA = (byte) 0b11111111;
    private volatile byte IODIRB = (byte) 0b11111111;
    private volatile byte IPOLA = (byte) 0b00000000;
    private volatile byte IPOLB = (byte) 0b00000000;
    private volatile byte GPINTENA = (byte) 0b00000000;
    private volatile byte GPINTENB = (byte) 0b00000000;
    private volatile byte DEFVALA = (byte) 0b00000000;
    private volatile byte DEFVALB = (byte) 0b00000000;
    private volatile byte INTCONA = (byte) 0b00000000;
    private volatile byte INTCONB = (byte) 0b00000000;
    // private volatile byte IOCON = (byte) 0b00000000;  // Unused
    private volatile byte GPPUA = (byte) 0b00000000;
    private volatile byte GPPUB = (byte) 0b00000000;
    private volatile byte OLATA = (byte) 0b00000000;
    private volatile byte OLATB = (byte) 0b00000000;
    private volatile byte GPIOA = (byte) 0b00000000;
    private volatile byte GPIOB = (byte) 0b00000000;

    /**
     * This is the only private constructor. --the static factory methods must be used for object creation.
     * <p>
     * Note that the Address pins are disabled by default. The factory method enables them.
     *
     * @param spi             the chip is wired to this interface (SPI).
     * @param hardWareAddress The Hardware address of this very MCP23S17 IC.
     * @param portAInterrupt  the pin where INTA of this chip is connected
     * @param portBInterrupt  the pin where INTB of this chip is connected
     * @param readGPIO        whether to read from GPIO registers in addition to INTCAP registers on interrupt.
     * @throws NullPointerException     if the given chip select output is {@code null}.
     * @throws IllegalArgumentException if the hardware address is outside the range 0-7
     */
    private MCP23S17(Spi spi,
                     int hardWareAddress,
                     DigitalInput portAInterrupt,
                     DigitalInput portBInterrupt,
                     boolean readGPIO) {
        this.readGPIORegisterOnInterrupt = readGPIO;
        this.spi = nonNull(spi, "SPI");
        this.portAInterrupt = portAInterrupt;
        this.portBInterrupt = portBInterrupt;
        //check whether the Address is in the correct range
        if (hardWareAddress > 7 || hardWareAddress < 0) {
            throw new IllegalArgumentException(strFormat(EXC_HW_ADDR_OUTSIDE_0_TO_7, hardWareAddress));
        }
        //the hardWareAddress is the three bits before the Read/Write bit:
        //0b0100xxx0 to write to address xxx
        //0b0100xxx1 to read from address xxx
        this.readByte = (byte) (READ_OPCODE | (hardWareAddress << 1));
        this.writeByte = (byte) (WRITE_OPCODE | (hardWareAddress << 1));
    }

    private static String strFormat(String fstr, Object... objs) {
        return MessageFormatter.basicArrayFormat(fstr, objs);
    }

    private static <T> T nonNull(T obj, String name) {
        return Objects.requireNonNull(obj, name + " must be non-null");
    }

    /**
     * Instantiate a new {@code MCP23S17} object with no interrupts.
     *
     * @param bus the chip is wired to this interface (SPI).
     * @return a new {@code MCP23S17} object with no interrupts.
     * @throws NullPointerException if the given chip select output is {@code null}.
     */
    public static MCP23S17 newWithoutInterrupts(Spi bus) {
        return new MCP23S17(bus, 0, null, null, false);
    }

    /**
     * Instantiate a number of {@code MCP23S17} objects on the same bus with consecutive addresses.
     *
     * @param bus    the chip is wired to this interface (SPI).
     * @param amount the amount of chips on the bus. must be between 1 and 8
     * @return an {@link ArrayList} of {@code MCP23S17} objects with no interrupts.
     * @throws IllegalArgumentException if there are too few/many chips on the bus ({@code amount} not in range 1-8)
     * @throws NullPointerException     if the given chip select output is {@code null}.
     */
    public static List<MCP23S17> multipleNewOnSameBus(Spi bus, int amount) {
        if (amount > 8 || amount < 1) {
            throw new IllegalArgumentException(strFormat(EXC_AMOUNT_OUTSIDE_1_TO_8, amount));
        }

        var integratedCircuitList = new ArrayList<MCP23S17>(amount);
        for (int i = 0; i < amount; ++i) {
            integratedCircuitList.add(new MCP23S17(bus, i, null, null, false));
        }

        //need to enable the hardware address pins by sending the appropriate address write command.
        //this enables every chip assuming they are connected to the same SPI bus and Chip select
        integratedCircuitList.get(0).write(ADDR_IOCON, (byte) 0b00001000);

        return integratedCircuitList;
    }

    /**
     * Instantiate multiple new {@code MCP23S17} objects on the same SPI-bus with their hardware address pins enabled
     * and with their port A and port B interrupt lines "tied" together.
     *
     * @param bus        the chips are wired to this interface (SPI).
     * @param interrupts an array of {@link DigitalInput}s to listen for interrupts from the chips
     * @param amount     the amount of ICs on the bus
     * @param readGPIO   true if on interrupt the GPIO registers should be read instead of the INTCAP registers
     * @return all the newly instantiated ICs
     * @throws IllegalArgumentException if the amount isn't in the range 1-8 or
     *                                  {@code interrupts.length} is smaller than amount
     * @throws NullPointerException     if the {@code interrupts} array contains null.
     */
    public static List<MCP23S17> multipleNewOnSameBusWithTiedInterrupts(Spi bus,
                                                                        DigitalInput[] interrupts,
                                                                        int amount,
                                                                        boolean readGPIO) {

        if (amount > 8 || amount < 1) {
            throw new IllegalArgumentException(strFormat(EXC_AMOUNT_OUTSIDE_1_TO_8, amount));
        }
        if (interrupts.length < amount) {
            throw new IllegalArgumentException(strFormat(EXC_AMOUNT_BIGGER_THAN_INTERRUPT_ARRAY, amount, interrupts.length));
        }

        var integratedCircuitList = new ArrayList<MCP23S17>(amount);

        for (int i = 0; i < amount; ++i) {
            var currentIC = new MCP23S17(
                    bus,
                    i,
                    nonNull(interrupts[i], "interrupts"),
                    interrupts[i],
                    readGPIO);
            integratedCircuitList.add(currentIC);

            DigitalInput interrupt = interrupts[i];
            attachInterruptOnLow(interrupt, () -> {
                int j = 0;
                do {
                    currentIC.handlePortAInterrupt();
                    currentIC.handlePortBInterrupt();
                    delay(10);
                    ++j;
                } while (interrupt.state().isLow() && j < 1); //TODO: this is a failsave for production. sometimes a single read didn't suffice. But it is very hacky and sucks for automated testing. Should definitely be addressed
                if (j > 1) {
                    LOG.warn("read {} times to clear interrupt.", j);
                }
            });
        }


        // Set the IOCON.MIRROR bit to OR the INTA and INTB lines together.
        // should address ALL chips as the address pins aren't enabled yet
        byte mirrorAndHAEN = 0x40;
        //need to enable the hardware address pins by sending the appropriate address write command.
        //this enables every chip assuming they are connected to the same SPI bus and Chip select
        mirrorAndHAEN |= 0b00001000;
        integratedCircuitList.get(0).write(ADDR_IOCON, mirrorAndHAEN);
        //last chip somehow doesn't tie its interrupt together. Maybe capacitance because it is
        //the last one on the bus? makes little sense.
        //anyway, writing to the last one specifically and telling it to tie its interrupts
        //together works
        integratedCircuitList.get(integratedCircuitList.size() - 1).write(ADDR_IOCON, mirrorAndHAEN);

        return integratedCircuitList;
    }

    /**
     * Instantiate a new {@code MCP23S17} object with the port A and port B interrupt lines "tied" together.
     *
     * @param bus       the chip is wired to this interface (SPI).
     * @param interrupt the interrupt {@linkplain DigitalInput input pin}.
     * @return a new {@code MCP23S17} object with the port A and port B interrupt lines "tied" together.
     * @throws NullPointerException if the given chip select output or tied interrupt input is {@code null}.
     */
    public static MCP23S17 newWithTiedInterrupts(Spi bus,
                                                 DigitalInput interrupt) {
        MCP23S17 ioExpander = new MCP23S17(
                bus,
                0,
                nonNull(interrupt, "interrupt"),
                interrupt,
                false);
        // Set the IOCON.MIRROR bit to OR the INTA and INTB lines together.
        ioExpander.write(ADDR_IOCON, (byte) 0x40);
        attachInterruptOnLow(interrupt, () -> {
            ioExpander.handlePortAInterrupt();
            ioExpander.handlePortBInterrupt();
        });
        return ioExpander;
    }

    /**
     * Instantiate a new {@code MCP23S17} object with individual port A and port B interrupt lines.
     *
     * @param bus            the chip is wired to this interface (SPI).
     * @param portAInterrupt the interrupt {@linkplain DigitalInput input pin} for port A.
     * @param portBInterrupt the interrupt {@linkplain DigitalInput input pin} for port B.
     * @return a new {@code MCP23S17} object with individual port A and port B interrupt lines.
     * @throws NullPointerException if the given chip select output or either of the interrupt inputs is {@code null}.
     */
    public static MCP23S17 newWithInterrupts(Spi bus,
                                             DigitalInput portAInterrupt,
                                             DigitalInput portBInterrupt) {
        MCP23S17 ioExpander = new MCP23S17(
                bus,
                0,
                nonNull(portAInterrupt, "portAInterrupt"),
                nonNull(portBInterrupt, "portBInterrupt"),
                false);
        attachInterruptOnLow(portAInterrupt, ioExpander::handlePortAInterrupt);
        attachInterruptOnLow(portBInterrupt, ioExpander::handlePortBInterrupt);
        return ioExpander;
    }

    /**
     * Instantiate a new {@code MCP23S17} object with an individual port A interrupt line, but no port B interrupt line.
     *
     * @param bus            the chip is wired to this interface (SPI).
     * @param portAInterrupt the interrupt {@linkplain DigitalInput input pin} for port A.
     * @return a new {@code MCP23S17} object with an individual port A interrupt line, but no port B interrupt line.
     * @throws NullPointerException if the given chip select output or the port A interrupt inputs is {@code null}.
     */
    public static MCP23S17 newWithPortAInterrupts(Spi bus,
                                                  DigitalInput portAInterrupt) {
        MCP23S17 ioExpander = new MCP23S17(
                bus,
                0,
                nonNull(portAInterrupt, "portAInterrupt"),
                null,
                false);
        attachInterruptOnLow(portAInterrupt, ioExpander::handlePortAInterrupt);
        return ioExpander;
    }

    /**
     * Instantiate a new {@code MCP23S17} object with an individual port B interrupt line, but no port A interrupt line.
     *
     * @param bus            the chip is wired to this interface (SPI).
     * @param portBInterrupt the interrupt {@linkplain DigitalInput input pin} for port B.
     * @return a new {@code MCP23S17} object with an individual port B interrupt line, but no port A interrupt line.
     * @throws NullPointerException if the given chip select output or the port B interrupt inputs is {@code null}.
     */
    public static MCP23S17 newWithPortBInterrupts(Spi bus,
                                                  DigitalInput portBInterrupt) {
        MCP23S17 ioExpander = new MCP23S17(
                bus,
                0,
                null,
                nonNull(portBInterrupt, "portBInterrupt"),
                false);
        attachInterruptOnLow(portBInterrupt, ioExpander::handlePortBInterrupt);
        return ioExpander;
    }

    /**
     * Attach a {@link Runnable Runnable} callback to the given {@linkplain DigitalInput input pin} to be invoked
     * whenever the pin goes low.
     *
     * @param interrupt the input pin to attach the interrupt to.
     * @param callback  the {@code Runnable} callback.
     */
    private static void attachInterruptOnLow(DigitalInput interrupt, Runnable callback) {
        interrupt.addListener(event -> {
            if (event.state().isLow()) {
                callback.run();
            }
        });
    }

    /**
     * Utility function to sleep for the specified amount of milliseconds.
     * An {@link InterruptedException} will be caught and ignored while setting the interrupt flag again.
     *
     * @param milliseconds Time in milliseconds to sleep
     */
    private static void delay(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Get the {@link PinView PinView} corresponding to the given {@link Pin Pin}.
     *
     * @param pin the {@code Pin}.
     * @return the corresponding {@code PinView}.
     */
    public PinView getPinView(Pin pin) {
        // This is called from callInterruptListeners when an interrupt occurs, hence the need for sync.
        synchronized (pinViews) {
            return pinViews.computeIfAbsent(nonNull(pin, "pin"), PinView::new);
        }
    }

    /**
     * <p>
     * Get an {@link Iterator Iterator} over all the {@link PinView PinView}s for this {@code MCP23S17}.
     * </p>
     * <p>
     * The returned {@code Iterator} returns the {@code PinView}s in the order of their pin numbers (i.e. the
     * {@code PinView} for {@link Pin#PIN0 PIN0} comes first and the {@code PinView} for {@link Pin#PIN15 PIN15} comes
     * last). The returned {@code Iterator} does not support {@linkplain Iterator#remove() removal of elements}.
     * </p>
     * <p>
     * Note that if certain {@code PinView}s have not yet been lazily-loaded, they will be loaded as needed by the
     * returned {@code Iterator}.
     * </p>
     *
     * @return an iterator over all the {@link PinView PinView}s for this {@code MCP23S17}.
     */
    public Iterator<PinView> getPinViewIterator() {
        return new Iterator<>() {

            private int current = 0;

            @Override
            public boolean hasNext() {
                return current < 16;
            }

            @Override
            public PinView next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return getPinView(Pin.fromPinNumber(current++));
            }
        };
    }

    /**
     * preconfigures all 16 pins of the MCP23S17 to pulled-up interrupt-inputs
     * The correct values are also being written to the corresponding registers and the
     * GPIO registers are read once to clear any pending interrupts on startup.
     *
     * @return an {@link ArrayList} of 16 {@linkplain PinView} objects
     * that are all configured to be inpputs, pulled up and interrupt-on-change enabled.
     * @throws IOException when any read or write operation fails.
     */
    public List<PinView> getAllPinsAsPulledUpInterruptInput() throws IOException {
        var pinList = new ArrayList<PinView>(16);
        var iterator = getPinViewIterator();
        while (iterator.hasNext()) {
            var pv = iterator.next();
            pv.setAsInput();
            pv.enablePullUp();
            pv.enableInterrupt();
            pinList.add(pv);
        }
        writeIODIRA();
        writeIODIRB();
        writeGPPUA();
        writeGPPUB();
        writeGPINTENA();
        writeGPINTENB();
        //read the GPIO register to clear first pending interrupt
        readGPIOA();
        readGPIOB();
        return pinList;
    }

    /**
     * <p>
     * Add a global {@linkplain InterruptListener interrupt listener}.
     * </p>
     * <p>
     * This does no error checking with respect to whether or not interrupts are enabled.
     * </p>
     *
     * @param listener the global listener to add.
     * @throws IllegalArgumentException if the given global listener is already registered.
     * @throws NullPointerException     if the given global listener is {@code null}.
     * @implSpec This is synchronized on the collection of global listeners, so it is thread safe.
     */
    public void addGlobalListener(InterruptListener listener) {
        synchronized (globalListeners) {
            if (globalListeners.contains(
                    nonNull(listener, "listener")
            )) {
                throw new IllegalArgumentException("global listener already registered");
            }
            globalListeners.add(listener);
        }
    }

    /**
     * Remove a global {@linkplain InterruptListener interrupt listener}.
     *
     * @param listener the global listener to remove.
     * @throws IllegalArgumentException if the given global listener was not previously registered.
     * @throws NullPointerException     if the given global listener is {@code null}.
     * @implSpec This is synchronized on the collection of global listeners, so it is thread safe.
     */
    public void removeGlobalListener(InterruptListener listener) {
        synchronized (globalListeners) {
            if (!globalListeners.contains(
                    nonNull(listener, "listener")
            )) {
                throw new IllegalArgumentException("cannot remove unregistered global listener");
            }
            globalListeners.remove(listener);
        }
    }

    /**
     * Initiate SPI communication with the chip and write a byte to the register pointed to by the given address.
     *
     * @param registerAddress the register address.
     * @param value           the value to write to the register.
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    private void write(byte registerAddress, byte value) {
        // Without testing it is unclear whether the synchronization here is necessary--the documentation on write
        // is poor.
        synchronized (spi) {
            spi.write(writeByte, registerAddress, value);
        }
    }

    /**
     * Initiate SPI communication with the chip and write the IODIRA byte to the IODIRA register.
     *
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public void writeIODIRA() {
        write(ADDR_IODIRA, IODIRA);
    }

    /**
     * Initiate SPI communication with the chip and write the IODIRB byte to the IODIRB register.
     *
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public void writeIODIRB() {
        write(ADDR_IODIRB, IODIRB);
    }

    /**
     * Initiate SPI communication with the chip and write the IPOLA byte to the IPOLA register.
     *
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public void writeIPOLA() {
        write(ADDR_IPOLA, IPOLA);
    }

    /**
     * Initiate SPI communication with the chip and write the IPOLB byte to the IPOLB register.
     *
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public void writeIPOLB() {
        write(ADDR_IPOLB, IPOLB);
    }

    /**
     * Initiate SPI communication with the chip and write the GPINTENA byte to the GPINTENA register.
     *
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public void writeGPINTENA() {
        write(ADDR_GPINTENA, GPINTENA);
    }

    /**
     * Initiate SPI communication with the chip and write the GPINTENB byte to the GPINTENB register.
     *
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public void writeGPINTENB() {
        write(ADDR_GPINTENB, GPINTENB);
    }

    /**
     * Initiate SPI communication with the chip and write the DEFVALA byte to the DEFVALA register.
     *
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public void writeDEFVALA() {
        write(ADDR_DEFVALA, DEFVALA);
    }

    /**
     * Initiate SPI communication with the chip and write the DEFVALB byte to the DEFVALB register.
     *
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public void writeDEFVALB() {
        write(ADDR_DEFVALB, DEFVALB);
    }

    /**
     * Initiate SPI communication with the chip and write the INTCONA byte to the INTCONA register.
     *
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public void writeINTCONA() {
        write(ADDR_INTCONA, INTCONA);
    }

    /**
     * Initiate SPI communication with the chip and write the INTCONB byte to the INTCONB register.
     *
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public void writeINTCONB() {
        write(ADDR_INTCONB, INTCONB);
    }

    /**
     * Initiate SPI communication with the chip and write the GPPUA byte to the GPPUA register.
     *
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public void writeGPPUA() {
        write(ADDR_GPPUA, GPPUA);
    }

    /**
     * Initiate SPI communication with the chip and write the GPPUB byte to the GPPUB register.
     *
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public void writeGPPUB() {
        write(ADDR_GPPUB, GPPUB);
    }

    /**
     * Initiate SPI communication with the chip and write the OLATA byte to the OLATA register.
     *
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public void writeOLATA() {
        write(ADDR_OLATA, OLATA);
    }

    /**
     * Initiate SPI communication with the chip and write the OLATB byte to the OLATB register.
     *
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public void writeOLATB() {
        write(ADDR_OLATB, OLATB);
    }

    /**
     * Initiate SPI communication with the chip and read a byte from the register pointed to by the given address.
     *
     * @param registerAddress the register address.
     * @return the byte read from the register.
     * @throws IOException if the SPI read procedure fails.
     * @implSpec This is synchronized on the {@link Spi Spi} object so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    private byte read(byte registerAddress) throws IOException {
        byte[] data = new byte[3];
        // The 0x00 byte is just arbitrary filler.
        byte[] send = {readByte, registerAddress, (byte) 0x00};
        synchronized (spi) {
            int res;
            res = spi.transfer(send, data);
            if (res < 0) {
                throw new IOException("oh noes! SPI transfer result was negative: " + res);
            }
        }
        return data[2];
    }

    /**
     * Initiate SPI communication with the chip and read the GPIOA register.
     *
     * @return the register's byte
     * @throws IOException if the SPI write procedure fails.
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public byte readGPIOA() throws IOException {
        GPIOA = read(ADDR_GPIOA);
        return GPIOA;
    }

    /**
     * Initiate SPI communication with the chip and read the GPIOB register.
     *
     * @return the register's byte
     * @throws IOException if the SPI write procedure fails.
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public byte readGPIOB() throws IOException {
        GPIOB = read(ADDR_GPIOB);
        return GPIOB;
    }

    /**
     * Initiate SPI communication with the chip and read the GPINTENA register.
     *
     * @return the register's byte
     * @throws IOException if the SPI write procedure fails.
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public byte readGPINTENA() throws IOException {
        return read(ADDR_GPINTENA);
    }

    /**
     * Initiate SPI communication with the chip and read the GPINTENB register.
     *
     * @return the register's byte
     * @throws IOException if the SPI write procedure fails.
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public byte readGPINTENB() throws IOException {
        return read(ADDR_GPINTENB);
    }

    /**
     * Initiate SPI communication with the chip and read the IOCON register.
     *
     * @return the register's byte
     * @throws IOException if the SPI write procedure fails.
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    public byte readIOCON() throws IOException {
        return read(ADDR_IOCON);
    }

    /**
     * Initiate SPI communication with the chip and read a byte from the register pointed to by the given address. This
     * will rethrow any {@link IOException IOException}s that occur as {@link RuntimeException RuntimeException}s.
     *
     * @param registerAddress the register address.
     * @return the byte read from the register.
     * @throws RuntimeException if the SPI read procedure fails (rethrown from {@code IOException}).
     * @implSpec This is synchronized on the {@link Spi Spi} so that two or more reads/writes cannot be
     * initiated at the same time.
     */
    private byte uncheckedRead(byte registerAddress) {
        try {
            return read(registerAddress);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * This is the callback for when port A interrupts occur. Read the INTFA and INTCAPA and maybe GPIOA registers
     * depending on {@code readGPIORegisterOnInterrupt} and alert all the
     * appropriate {@linkplain InterruptListener interrupt listeners} of the interrupt.
     */
    private void handlePortAInterrupt() {
        byte interruptMask = uncheckedRead(ADDR_INTFA);
        byte interruptCapture = uncheckedRead(ADDR_INTCAPA);

        if (readGPIORegisterOnInterrupt) {
            byte currentCapture = uncheckedRead(ADDR_GPIOA);
            //if GPIO already differs from INTCAP then ignore. This solves the problem that in practice,
            //neighbouring pins might get pulled down because of capacitance or something.
            interruptMask &= ~(interruptCapture ^ currentCapture);
        }
        callInterruptListeners(interruptMask, interruptCapture, PORT_A_PINS);
    }

    /**
     * This is the callback for when port B interrupts occur. Read the INTFB and INTCAPB and maybe GPIOB registers
     * depending on {@code readGPIORegisterOnInterrupt} and alert all the
     * appropriate {@linkplain InterruptListener interrupt listeners} of the interrupt.
     */
    private void handlePortBInterrupt() {
        byte interruptMask = uncheckedRead(ADDR_INTFB);
        byte interruptCapture = uncheckedRead(ADDR_INTCAPB);

        if (readGPIORegisterOnInterrupt) {
            byte currentCapture = uncheckedRead(ADDR_GPIOB);
            //if GPIO already changed from INTCAP then ignore.
            interruptMask &= ~(interruptCapture ^ currentCapture);
        }
        callInterruptListeners(interruptMask, interruptCapture, PORT_B_PINS);
    }

    /**
     * Using the given state of the INTFx register, determine which of the given {@linkplain Pin pins} is responsible
     * for generating an interrupt. Then, call all the global listeners and pin-specific listeners for the pin which
     * generated the interrupt with the captured value of the pin at the time of the interrupt.
     *
     * @param intf   the state of the INTFx register at the time of the interrupt.
     * @param intcap the state of the INTCAPx register at the time of the interrupt.
     * @param pins   either {@link MCP23S17#PORT_A_PINS PORT_A_PINS} or {@link MCP23S17#PORT_B_PINS PORT_B_PINS},
     *               whichever is appropriate.
     */
    private void callInterruptListeners(byte intf, byte intcap, Pin[] pins) {
        for (Pin pin : pins) {
            if (pin.getCorrespondingBit(intf)) {
                boolean capturedValue = pin.getCorrespondingBit(intcap);
                synchronized (globalListeners) {
                    for (InterruptListener listener : globalListeners) {
                        listener.onInterrupt(capturedValue, pin);
                    }
                }
                // This can in rare cases where the IO expander is already configured create a PinView object before the
                // user indirectly creates it lazily...
                getPinView(pin).relayInterruptToListeners(capturedValue);
            }
        }
    }

    /**
     * get the pi4j {@link Spi} object.
     * <p>
     * Useful when mocking SPI in tests
     *
     * @return the pi4j {@link Spi} object
     */
    public Spi getSpi() {
        return spi;
    }

    /**
     * <p>
     * Enum for each physical GPIO pin on the MCP23S17 chip.
     * </p>
     * <p>
     * The pins are renumbered so that port A's pins are in order as {@code PIN0} through {@code PIN7} and port B's pins
     * are in order as {@code PIN8} through {@code PIN15}.
     * </p>
     *
     * @author Robert Russell
     */
    public enum Pin {
        // Port A
        PIN0(0, true),
        PIN1(1, true),
        PIN2(2, true),
        PIN3(3, true),
        PIN4(4, true),
        PIN5(5, true),
        PIN6(6, true),
        PIN7(7, true),

        // Port B
        PIN8(0, false),
        PIN9(1, false),
        PIN10(2, false),
        PIN11(3, false),
        PIN12(4, false),
        PIN13(5, false),
        PIN14(6, false),
        PIN15(7, false);

        private final int pinNumber;
        private final boolean portA;
        /**
         * A bit mask which is used in {@link Pin#getCorrespondingBit(byte) getCorrespondingBit} and
         * {@link Pin#setCorrespondingBit(byte, boolean) setCorrespondingBit}.
         */
        private final byte mask;

        /**
         * @param bitIndex the index of the pin in the bytes which concern it (i.e. the pin number <code>mod 8</code>).
         * @param portA    whether or not this pin is in port A.
         */
        Pin(int bitIndex, boolean portA) {
            this.pinNumber = bitIndex + (portA ? 0 : 8);
            this.portA = portA;
            this.mask = (byte) (1 << bitIndex);
        }

        /**
         * Get the {@code Pin} corresponding to the given pin number.
         *
         * @param pinNumber the pin number.
         * @return the {@code Pin} corresponding to the given pin number.
         * @throws IllegalArgumentException if the given pin number is invalid (i.e. less than 0 or greater than 15).
         */
        public static Pin fromPinNumber(int pinNumber) {
            return switch (pinNumber) {
                // Port A
                case 0 -> PIN0;
                case 1 -> PIN1;
                case 2 -> PIN2;
                case 3 -> PIN3;
                case 4 -> PIN4;
                case 5 -> PIN5;
                case 6 -> PIN6;
                case 7 -> PIN7;

                // Port B
                case 8 -> PIN8;
                case 9 -> PIN9;
                case 10 -> PIN10;
                case 11 -> PIN11;
                case 12 -> PIN12;
                case 13 -> PIN13;
                case 14 -> PIN14;
                case 15 -> PIN15;
                default -> throw new IllegalArgumentException("illegal pin number");
            };
        }

        /**
         * <p>
         * Get the pin number.
         * </p>
         * <p>
         * The pins are numbered so that port A's pins are in order as {@code 0} through {@code 7} and port B's pins are
         * in order as {@code 8} through {@code 15}.
         * </p>
         *
         * @return the pin number.
         */
        public int getPinNumber() {
            return pinNumber;
        }

        /**
         * Get whether or not this {@code Pin} is in port A.
         *
         * @return whether or not this {@code Pin} is in port A.
         */
        public boolean isPortA() {
            return portA;
        }

        /**
         * Get whether or not this {@code Pin} is in port B.
         *
         * @return whether or not this {@code Pin} is in port B.
         */
        public boolean isPortB() {
            return !portA;
        }

        /**
         * Given two bytes, one corresponding to port A and one corresponding to port B, return the one corresponding to
         * the port which this {@code Pin} is in.
         *
         * @param byteA the byte corresponding to port A.
         * @param byteB the byte corresponding to port B.
         * @return the byte corresponding to the port which this {@code Pin} is in.
         */
        private byte resolveCorrespondingByte(byte byteA, byte byteB) {
            if (isPortA()) {
                return byteA;
            }
            return byteB;
        }

        /**
         * Given a byte, index it and return the bit corresponding to this pin. (The index is given by this
         * {@code Pin}'s {@linkplain Pin#getPinNumber() pin number} <code>mod 8</code>.)
         *
         * @param b the byte to index.
         * @return the bit corresponding to this pin.
         */
        private boolean getCorrespondingBit(byte b) {
            return Byte.toUnsignedInt((byte) (b & mask)) > 0;
        }

        /**
         * Given a byte, set the bit corresponding to this pin to the given value and return the resulting byte. (The
         * index at which the bit is set in the byte is given by this {@code Pin}'s
         * {@linkplain Pin#getPinNumber() pin number} <code>mod 8</code>.)
         *
         * @param b     the byte to set/clear the bit in.
         * @param value the value to set at the corresponding bit.
         * @return the resulting byte.
         */
        private byte setCorrespondingBit(byte b, boolean value) {
            if (value) {
                return (byte) (b | mask);
            }
            return (byte) (b & ~mask);
        }
    }

    /**
     * A {@linkplain FunctionalInterface functional interface} representing an interrupt listener callback.
     * {@code InterruptListener}s are registered via
     * {@link MCP23S17#addGlobalListener(InterruptListener) addGlobalListener} on {@link MCP23S17 MCP23S17} objects and
     * {@link PinView#addListener(InterruptListener) addListener} on {@link PinView PinView} objects, and removed by the
     * similarly-named methods.
     *
     * @author Robert Russell
     */
    @FunctionalInterface
    public interface InterruptListener {

        /**
         * Called whenever an interrupt occurs on a pin for which this listener is registered to receive events.
         *
         * @param capturedValue the value on the pin at the time of the interrupt.
         * @param pin           the {@link Pin Pin} on which the interrupt occurred.
         */
        void onInterrupt(boolean capturedValue, Pin pin);
    }

    /**
     * An abstraction of each physical GPIO pin on a MCP23S17 IO expander chip.
     *
     * @author Robert Russell
     * @see MCP23S17
     */
    public final class PinView {

        private final Pin pin;

        /**
         * The listeners registered specifically to this pin (i.e. not global listeners). This object is synchronized on
         * so that listeners cannot be added or removed while an interrupt is being processed or vice versa.
         */
        private final Collection<InterruptListener> listeners = new HashSet<>(0);

        private PinView(Pin pin) {
            this.pin = pin;
        }

        /**
         * Get the {@link Pin Pin} which this {@code PinView} represents.
         *
         * @return the {@link Pin Pin} which this {@code PinView} represents.
         */
        public Pin getPin() {
            return pin;
        }

        /**
         * Get the state of the pin from the corresponding bit in the corresponding OLATx or GPIOx byte
         * (note: not the actual value in the MCP23S17's OLATx register, see {@link PinView#getFromRead()}).
         *
         * @return the state of the pin.
         */
        public boolean get() {
            if (isOutput()) {
                return pin.getCorrespondingBit(pin.resolveCorrespondingByte(OLATA, OLATB));
            }
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(GPIOA, GPIOB));
        }

        /**
         * Get the state of the input pin by reading the correct register of the actual Chip.
         *
         * @return the state of the input pin.
         * @throws IOException if SPI communication failed or if the chip is configured as output
         */
        public boolean getFromRead() throws IOException {
            if (isOutput()) {
                throw new IOException("getFromRead() not supported for pins that are outputs");
            }
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(readGPIOA(), readGPIOB()));
        }

        /**
         * Set the pin to the given state. More specifically, this sets the corresponding bit in the corresponding OLATx
         * byte (note: not the actual value in the MCP23S17's OLATx register) to the given state.
         *
         * @param value the value to set on the pin.
         */
        public void set(boolean value) {
            synchronized (byteWriteLock) {
                if (pin.isPortA()) {
                    OLATA = pin.setCorrespondingBit(OLATA, value);
                } else {  // portB
                    OLATB = pin.setCorrespondingBit(OLATB, value);
                }
            }
        }

        /**
         * Sets the pin high. More specifically, this sets the corresponding bit in the corresponding OLATx byte (note:
         * not the actual value in the MCP23S17's OLATx register).
         */
        public void set() {
            set(true);
        }

        /**
         * Clears the pin (sets it low). More specifically, this clears the corresponding bit in the corresponding OLATx
         * byte (note: not the actual value in the MCP23S17's OLATx register).
         */
        public void clear() {
            set(false);
        }

        /**
         * Get whether or not this pin is input. More specifically, this returns the corresponding bit in the
         * corresponding IODIRx byte (note: not the actual value in the MCP23S17's IODIRx register).
         *
         * @return whether or not this pin is input.
         */
        public boolean isInput() {
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(IODIRA, IODIRB));
        }

        /**
         * Get whether or not this pin is output. More specifically, this returns the inverse of the corresponding bit
         * in the corresponding IODIRx byte (note: not the actual value in the MCP23S17's IODIRx register).
         *
         * @return whether or not this pin is output.
         */
        public boolean isOutput() {
            return !isInput();
        }

        /**
         * Set the pin to the given direction. More specifically, this sets the corresponding bit in the corresponding
         * IODIRx byte (note: not the actual value in the MCP23S17's IODIRx register) if the pin direction is input, and
         * clears it if the pin direction is output.
         *
         * @param input true for input; false for output.
         */
        public void setDirection(boolean input) {
            synchronized (byteWriteLock) {
                if (pin.isPortA()) {
                    IODIRA = pin.setCorrespondingBit(IODIRA, input);
                } else {  // portB
                    IODIRB = pin.setCorrespondingBit(IODIRB, input);
                }
            }
        }

        /**
         * Set the pin to be input. More specifically, this sets the corresponding bit in the corresponding IODIRx byte
         * (note: not the actual value in the MCP23S17's IODIRx register).
         */
        public void setAsInput() {
            setDirection(true);
        }

        /**
         * Set the pin to be output. More specifically, this clears the corresponding bit in the corresponding IODIRx
         * byte (note: not the actual value in the MCP23S17's IODIRx register).
         */
        public void setAsOutput() {
            setDirection(false);
        }

        /**
         * Get whether or not this pin's input is inverted. More specifically, this returns the corresponding bit in the
         * corresponding IPOLx byte (note: not the actual value in the MCP23S17's IPOLx register).
         *
         * @return whether or not this pin's input os inverted.
         */
        public boolean isInputInverted() {
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(IPOLA, IPOLB));
        }

        /**
         * Set whether the pin's input is inverted. More specifically, this sets the corresponding bit in the
         * corresponding IPOLx byte (note: not the actual value in the MCP23S17's IPOLx register) if the pin's input is
         * to be inverted, and clears it if the pin's input is to not be inverted.
         *
         * @param inverted whether the pin's input is to be inverted.
         */
        // TODO: this name is misleading and inconsistent; should be "isInputInverted"
        public void setInverted(boolean inverted) {
            synchronized (byteWriteLock) {
                if (pin.isPortA()) {
                    IPOLA = pin.setCorrespondingBit(IPOLA, inverted);
                } else {  // portB
                    IPOLB = pin.setCorrespondingBit(IPOLB, inverted);
                }
            }
        }

        /**
         * Set the pin's input to be inverted. More specifically, this sets the corresponding bit in the corresponding
         * IPOLx byte (note: not the actual value in the MCP23S17's IPOLx register).
         */
        public void invertInput() {
            setInverted(true);
        }

        /**
         * Set the pin's input to not be inverted. More specifically, this clears the corresponding bit in the
         * corresponding IPOLx byte (note: not the actual value in the MCP23S17's IPOLx register).
         */
        public void uninvertInput() {
            setInverted(false);
        }

        /**
         * Get whether or not this pin's interrupt is enabled. More specifically, this returns the corresponding bit in
         * the corresponding GPINTENx byte (note: not the actual value in the MCP23S17's GPINTENx register).
         *
         * @return whether or not this pin's interrupt is enabled.
         */
        public boolean isInterruptEnabled() {
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(GPINTENA, GPINTENB));
        }

        /**
         * Set whether or not the pin's interrupt is enabled. More specifically, this sets the corresponding bit in the
         * corresponding GPINTENx byte (note: not the actual value in the MCP23S17's GPINTENx register) if interrupts
         * are enabled, and clears it if interrupts are disabled.
         *
         * @param interruptEnabled whether or not to enable interrupts.
         */
        public void setInterruptEnabled(boolean interruptEnabled) {
            synchronized (byteWriteLock) {
                if (pin.isPortA()) {
                    GPINTENA = pin.setCorrespondingBit(GPINTENA, interruptEnabled);
                } else {  // portB
                    GPINTENB = pin.setCorrespondingBit(GPINTENB, interruptEnabled);
                }
            }
        }

        /**
         * Enable the pin's interrupts. More specifically, this sets the corresponding bit in the corresponding GPINTENx
         * byte (note: not the actual value in the MCP23S17's GPINTENx register).
         */
        public void enableInterrupt() {
            setInterruptEnabled(true);
        }

        /**
         * Disable the pin's interrupts. More specifically, this clears the corresponding bit in the corresponding
         * GPINTENx byte (note: not the actual value in the MCP23S17's GPINTENx register).
         */
        public void disableInterrupt() {
            setInterruptEnabled(false);
        }

        /**
         * Get the default comparison value for comparison interrupt mode for this pin. More specifically, this returns
         * the corresponding bit in the corresponding DEFVALx byte (note: not the actual value in the MCP23S17's DEFVALx
         * register).
         *
         * @return the default comparison value for comparison interrupt mode for this pin.
         */
        public boolean getDefaultComparisonValue() {
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(DEFVALA, DEFVALB));
        }

        /**
         * Set the default comparison value for comparison interrupt mode for this pin. More specifically, this sets the
         * corresponding bit in the corresponding DEFVALx byte (note: not the actual value in the MCP23S17's DEFVALx
         * register) to the given value.
         *
         * @param value the default comparison value for comparison interrupt mode for this pin.
         */
        public void setDefaultComparisonValue(boolean value) {
            synchronized (byteWriteLock) {
                if (pin.isPortA()) {
                    DEFVALA = pin.setCorrespondingBit(DEFVALA, value);
                } else {  // portB
                    DEFVALB = pin.setCorrespondingBit(DEFVALB, value);
                }
            }
        }

        /**
         * Get whether or not this pin is in interrupt comparison mode (as opposed to change mode). More specifically,
         * this returns the corresponding bit in the corresponding INTCONx byte (note: not the actual value in the
         * MCP23S17's INTCONx register).
         *
         * @return whether or not this pin is in interrupt comparison mode.
         */
        public boolean isInterruptComparisonMode() {
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(INTCONA, INTCONB));
        }

        /**
         * Get whether or not this pin is in interrupt change mode (as opposed to comparison mode). More specifically,
         * this returns the inverse of the corresponding bit in the corresponding INTCONx byte (note: not the actual
         * value in the MCP23S17's INTCONx register).
         *
         * @return whether or not this pin is in interrupt change mode.
         */
        public boolean isInterruptChangeMode() {
            return !isInterruptComparisonMode();
        }

        /**
         * Set the interrupt mode for this pin. More specifically, this sets the corresponding bit in the corresponding
         * INTCONx byte (note: not the actual value in the MCP23S17's INTCONx register) if transitioning to comparison
         * mode, and clears it if transitioning to change mode.
         *
         * @param comparison true for comparison mode; false for change mode.
         */
        public void setInterruptMode(boolean comparison) {
            synchronized (byteWriteLock) {
                if (pin.isPortA()) {
                    INTCONA = pin.setCorrespondingBit(INTCONA, comparison);
                } else {  // portB
                    INTCONB = pin.setCorrespondingBit(INTCONB, comparison);
                }
            }
        }

        /**
         * Set the interrupt mode to comparison mode. More specifically, this sets the corresponding bit in the
         * corresponding INTCONx byte (note: not the actual value in the MCP23S17's INTCONx register).
         */
        public void toInterruptComparisonMode() {
            setInterruptMode(true);
        }

        /**
         * Set the interrupt mode to change mode. More specifically, this clears the corresponding bit in the
         * corresponding INTCONx byte (note: not the actual value in the MCP23S17's INTCONx register).
         */
        public void toInterruptChangeMode() {
            setInterruptMode(false);
        }

        /**
         * Get whether or not this pin has a pull-up resistor enabled. More specifically, this returns the corresponding
         * bit in the corresponding GPPUx byte (note: not the actual value in the MCP23S17's GPPUx register).
         *
         * @return whether or not this pin has a pull-up resistor enabled.
         */
        public boolean isPulledUp() {
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(GPPUA, GPPUB));
        }

        /**
         * Set whether or not this pin has a pull-up resistor enabled. More specifically, this sets the corresponding
         * bit in the corresponding GPPUx byte (note: not the actual value in the MCP23S17's GPPUx register) if pull-up
         * resistors are being enabled, and clears it if pull-up resistors are being disabled.
         *
         * @param pulledUp whether or not to enable pull-up resistors.
         */
        public void setPulledUp(boolean pulledUp) {
            synchronized (byteWriteLock) {
                if (pin.isPortA()) {
                    GPPUA = pin.setCorrespondingBit(GPPUA, pulledUp);
                } else {  // portB
                    GPPUB = pin.setCorrespondingBit(GPPUB, pulledUp);
                }
            }
        }

        /**
         * Enables pull-up resistors for this pin. More specifically, this sets the corresponding bit in the
         * corresponding GPPUx byte (note: not the actual value in the MCP23S17's GPPUx register).
         */
        public void enablePullUp() {
            setPulledUp(true);
        }

        /**
         * Disables pull-up resistors for this pin. More specifically, this clears the corresponding bit in the
         * corresponding GPPUx byte (note: not the actual value in the MCP23S17's GPPUx register).
         */
        public void disablePullUp() {
            setPulledUp(false);
        }

        /**
         * <p>
         * Add an {@linkplain InterruptListener interrupt listener} for this pin.
         * </p>
         * <p>
         * This does no error checking with respect to whether or not interrupts are enabled for this pin or whether or
         * not they even could be effectively enabled.
         * </p>
         *
         * @param listener the listener to add.
         * @throws IllegalArgumentException if the given listener is already registered.
         * @throws NullPointerException     if the given listener is {@code null}.
         * @implSpec This is synchronized on the collection of listeners, so it is thread safe.
         */
        public void addListener(InterruptListener listener) {
            synchronized (listeners) {
                if (listeners.contains(nonNull(listener, "listener"))) {
                    throw new IllegalArgumentException("listener already registered");
                }
                listeners.add(listener);
            }
        }

        /**
         * Remove an {@linkplain InterruptListener interrupt listener} from this pin.
         *
         * @param listener the listener to remove.
         * @throws IllegalArgumentException if the given listener was not previously registered.
         * @throws NullPointerException     if the given listener is {@code null}.
         * @implSpec This is synchronized on the collection of listeners, so it is thread safe.
         */
        public void removeListener(InterruptListener listener) {
            synchronized (listeners) {
                if (!listeners.contains(nonNull(listener, "listener"))) {
                    throw new IllegalArgumentException("cannot remove unregistered listener");
                }
                listeners.remove(listener);
            }
        }

        /**
         * Invoke all the interrupt listeners registered to receive events specifically for this pin with the given
         * captured value. This is called from
         * {@link MCP23S17#callInterruptListeners(byte, byte, Pin[]) callInterruptListeners}.
         *
         * @param capturedValue the value on the pin at the time of the interrupt.
         */
        private void relayInterruptToListeners(boolean capturedValue) {
            synchronized (listeners) {
                for (InterruptListener listener : listeners) {
                    listener.onInterrupt(capturedValue, pin);
                }
            }
        }
    }
}
