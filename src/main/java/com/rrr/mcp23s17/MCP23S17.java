package com.rrr.mcp23s17;

import com.pi4j.io.gpio.GpioPinDigitalInput;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.event.GpioPinDigitalStateChangeEvent;
import com.pi4j.io.gpio.event.GpioPinListenerDigital;
import com.pi4j.io.spi.SpiChannel;
import com.pi4j.io.spi.SpiDevice;
import com.pi4j.io.spi.SpiFactory;
import com.pi4j.io.spi.SpiMode;

import java.io.IOException;
import java.util.*;

// TODO: doc -- register reads/writes are not thread safe!
public final class MCP23S17 {

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
        private final byte mask;

        Pin(int bitIndex, boolean portA) {
            this.pinNumber = bitIndex + (portA ? 0 : 8);
            this.portA = portA;
            this.mask = (byte) (1 << bitIndex);
        }

        public int getPinNumber() {
            return pinNumber;
        }

        public boolean isPortA() {
            return portA;
        }

        public boolean isPortB() {
            return !portA;
        }

        private byte resolveCorrespondingByte(byte byteA, byte byteB) {
            if (isPortA()) {
                return byteA;
            }
            return byteB;
        }

        private boolean getCorrespondingBit(byte b) {
            return (b & mask) > 0;
        }

        private byte setCorrespondingBit(byte b, boolean value) {
            if (value) {
                return (byte) (b | mask);
            }
            return (byte) (b & ~mask);
        }

        public static Pin fromPinNumber(int pinNumber) {
            switch (pinNumber) {
                case 0:
                    return PIN0;
                case 1:
                    return PIN1;
                case 2:
                    return PIN2;
                case 3:
                    return PIN3;
                case 4:
                    return PIN4;
                case 5:
                    return PIN5;
                case 6:
                    return PIN6;
                case 7:
                    return PIN7;

                case 8:
                    return PIN8;
                case 9:
                    return PIN9;
                case 10:
                    return PIN10;
                case 11:
                    return PIN11;
                case 12:
                    return PIN12;
                case 13:
                    return PIN13;
                case 14:
                    return PIN14;
                case 15:
                    return PIN15;

                default:
                    throw new IllegalArgumentException("illegal pin number");
            }
        }
    }

    public final class PinView {

        private final Pin pin;
        // TODO: doc -- we sync on this
        private final Collection<InterruptListener> listeners = new HashSet<>(0);

        private PinView(Pin pin) {
            this.pin = pin;
        }

        public Pin getPin() {
            return pin;
        }

        public void set(boolean value) throws IOException {
            if (pin.isPortA()) {
                OLATA = pin.setCorrespondingBit(OLATA, value);
                // write(ADDR_OLATA, OLATA);
            } else {  // portB
                OLATB = pin.setCorrespondingBit(OLATB, value);
                // write(ADDR_OLATB, OLATB);
            }
        }

        public boolean get() throws IOException {
            if (isOutput()) {
                return pin.getCorrespondingBit(pin.resolveCorrespondingByte(OLATA, OLATB));
            }
            return pin.getCorrespondingBit(read(pin.resolveCorrespondingByte(ADDR_GPIOA, ADDR_GPIOB)));
        }

        public boolean isInput() {
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(IODIRA, IODIRB));
        }

        public boolean isOutput() {
            return !isInput();
        }

        public void setDirection(boolean input) throws IOException {
            if (pin.isPortA()) {
                IODIRA = pin.setCorrespondingBit(IODIRA, input);
                // write(ADDR_IODIRA, IODIRA);
            } else {  // portB
                IODIRB = pin.setCorrespondingBit(IODIRB, input);
                // write(ADDR_IODIRB, IODIRB);
            }
        }

        public void setAsInput() throws IOException {
            setDirection(true);
        }

        public void setAsOutput() throws IOException {
            setDirection(false);
        }

        public boolean isInputInverted() {
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(IPOLA, IPOLB));
        }

        public void setInverted(boolean inverted) throws IOException {
            if (pin.isPortA()) {
                IPOLA = pin.setCorrespondingBit(IPOLA, inverted);
                // write(ADDR_IPOLA, IPOLA);
            } else {  // portB
                IPOLB = pin.setCorrespondingBit(IPOLB, inverted);
                // write(ADDR_IPOLB, IPOLB);
            }
        }

        public void invertInput() throws IOException {
            setInverted(true);
        }

        public void uninvertInput() throws IOException {
            setInverted(false);
        }

        public boolean isInterruptEnabled() {
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(GPINTENA, GPINTENB));
        }

        public void setInterruptEnabled(boolean interruptEnabled) throws IOException {
            if (pin.isPortA()) {
                GPINTENA = pin.setCorrespondingBit(GPINTENA, interruptEnabled);
                // write(ADDR_GPINTENA, GPINTENA);
            } else {  // portB
                GPINTENB = pin.setCorrespondingBit(GPINTENB, interruptEnabled);
                // write(ADDR_GPINTENB, GPINTENB);
            }
        }

        public void enableInterrupt() throws IOException {
            setInterruptEnabled(true);
        }

        public void disableInterrupt() throws IOException {
            setInterruptEnabled(false);
        }

        public boolean getDefaultComparisonValue() {
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(DEFVALA, DEFVALB));
        }

        public void setDefaultComparisonValue(boolean value) throws IOException {
            if (pin.isPortA()) {
                DEFVALA = pin.setCorrespondingBit(DEFVALA, value);
                // write(ADDR_DEFVALA, DEFVALA);
            } else {  // portB
                DEFVALB = pin.setCorrespondingBit(DEFVALB, value);
                // write(ADDR_DEFVALB, DEFVALB);
            }
        }

        public boolean isInterruptComparisonMode() {
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(INTCONA, INTCONB));
        }

        public boolean isInterruptChangeMode() {
            return !isInterruptComparisonMode();
        }

        public void setInterruptMode(boolean comparison) throws IOException {
            if (pin.isPortA()) {
                INTCONA = pin.setCorrespondingBit(INTCONA, comparison);
                // write(ADDR_INTCONA, INTCONA);
            } else {  // portB
                INTCONB = pin.setCorrespondingBit(INTCONB, comparison);
                // write(ADDR_INTCONB, INTCONB);
            }
        }

        public void toInterruptComparisonMode() throws IOException {
            setInterruptMode(true);
        }

        public void toInterruptChangeMode() throws IOException {
            setInterruptMode(false);
        }

        public boolean isPulledUp() {
            return pin.getCorrespondingBit(pin.resolveCorrespondingByte(GPPUA, GPPUB));
        }

        public void setPulledUp(boolean pulledUp) throws IOException {
            if (pin.isPortA()) {
                GPPUA = pin.setCorrespondingBit(GPPUA, pulledUp);
                // write(ADDR_GPPUA, GPPUA);
            } else {  // portB
                GPPUB = pin.setCorrespondingBit(GPPUB, pulledUp);
                // write(ADDR_GPPUB, GPPUB);
            }
        }

        public void enablePullUp() throws IOException {
            setPulledUp(true);
        }

        public void disablePullUp() throws IOException {
            setPulledUp(false);
        }

        public void addListener(InterruptListener listener) {
            synchronized (listeners) {
                if (listeners.contains(listener)) {
                    throw new IllegalArgumentException("listener already registered");
                }
                listeners.add(Objects.requireNonNull(listener, "cannot add null listener"));
            }
        }

        public void removeListener(InterruptListener listener) {
            synchronized (listeners) {
                if (!listeners.contains(listener)) {
                    throw new IllegalArgumentException("cannot remove unregistered listener");
                }
                listeners.remove(listener);
            }
        }

        private void relayInterruptToListeners(boolean capturedValue) {
            synchronized (listeners) {
                for (InterruptListener listener : listeners) {
                    listener.onInterrupt(capturedValue, pin);
                }
            }
        }
    }

    @FunctionalInterface
    public interface InterruptListener {

        void onInterrupt(boolean capturedValue, Pin pin);
    }

    private static final int SPI_SPEED_HZ = 1000000;  // 1 MHz; Max 10 MHz

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

    private static final byte WRITE_OPCODE = 0x40;
    private static final byte READ_OPCODE = 0x41;

    private static final Pin[] PORT_A_PINS =
            {Pin.PIN0, Pin.PIN1, Pin.PIN2, Pin.PIN3, Pin.PIN4, Pin.PIN5, Pin.PIN6, Pin.PIN7};
    private static final Pin[] PORT_B_PINS =
            {Pin.PIN8, Pin.PIN9, Pin.PIN10, Pin.PIN11, Pin.PIN12, Pin.PIN13, Pin.PIN14, Pin.PIN15};

    private final GpioPinDigitalOutput chipSelect;
    private final SpiDevice spi;
    // TODO: doc -- we sync on this
    private final EnumMap<Pin, PinView> pinViews = new EnumMap<>(Pin.class);
    // TODO: doc -- we sync on this
    private final Collection<InterruptListener> globalListeners = new HashSet<>(0);

    // These are only referenced so they are not GCed.
    private final GpioPinDigitalInput portAInterrupt;
    private final GpioPinDigitalInput portBInterrupt;

    private byte IODIRA = (byte) 0b11111111;
    private byte IODIRB = (byte) 0b11111111;
    private byte IPOLA = (byte) 0b00000000;
    private byte IPOLB = (byte) 0b00000000;
    private byte GPINTENA = (byte) 0b00000000;
    private byte GPINTENB = (byte) 0b00000000;
    private byte DEFVALA = (byte) 0b00000000;
    private byte DEFVALB = (byte) 0b00000000;
    private byte INTCONA = (byte) 0b00000000;
    private byte INTCONB = (byte) 0b00000000;
    // private byte IOCON = (byte) 0b00000000;  // Unused
    private byte GPPUA = (byte) 0b00000000;
    private byte GPPUB = (byte) 0b00000000;
    private byte OLATA = (byte) 0b00000000;
    private byte OLATB = (byte) 0b00000000;

    private MCP23S17(SpiChannel spiChannel,
                     GpioPinDigitalOutput chipSelect,
                     GpioPinDigitalInput portAInterrupt,
                     GpioPinDigitalInput portBInterrupt)
            throws IOException {
        this.chipSelect = Objects.requireNonNull(chipSelect, "chipSelect must be non-null");
        this.spi = SpiFactory.getInstance(
                Objects.requireNonNull(spiChannel, "spiChannel must be non-null"),
                SPI_SPEED_HZ,
                SpiMode.MODE_0
        );
        this.portAInterrupt = portAInterrupt;
        this.portBInterrupt = portBInterrupt;

        // Take the CS pin high if it is not already since the CS is active low.
        chipSelect.high();
    }

    public PinView getPinView(Pin pin) {
        PinView pinView;
        // This is called from callInterruptListeners when an interrupt occurs, hence the need for sync.
        synchronized (pinViews) {
            pinView = pinViews.get(Objects.requireNonNull(pin, "pin must be non-null"));
            if (pinView == null) {
                pinView = new PinView(pin);
                pinViews.put(pin, pinView);
            }
        }
        return pinView;
    }

    public Iterator<PinView> getPinViewIterator() {
        return new Iterator<PinView>() {

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

    public void addGlobalListener(InterruptListener listener) {
        synchronized (globalListeners) {
            if (globalListeners.contains(listener)) {
                throw new IllegalArgumentException("listener already registered");
            }
            globalListeners.add(Objects.requireNonNull(listener, "cannot add null listener"));
        }
    }

    public void removeGlobalListener(InterruptListener listener) {
        synchronized (globalListeners) {
            if (!globalListeners.contains(listener)) {
                throw new IllegalArgumentException("cannot remove unregistered listener");
            }
            globalListeners.remove(listener);
        }
    }

    private void write(byte registerAddress, byte value) throws IOException {
        try {
            chipSelect.low();
            spi.write(WRITE_OPCODE, registerAddress, value);
        } finally {
            chipSelect.high();
        }
    }

    public void writeIODIRA() throws IOException {
        write(ADDR_IODIRA, IODIRA);
    }

    public void writeIODIRB() throws IOException {
        write(ADDR_IODIRB, IODIRB);
    }

    public void writeIPOLA() throws IOException {
        write(ADDR_IPOLA, IPOLA);
    }

    public void writeIPOLB() throws IOException {
        write(ADDR_IPOLB, IPOLB);
    }

    public void writeGPINTENA() throws IOException {
        write(ADDR_GPINTENA, GPINTENA);
    }

    public void writeGPINTENB() throws IOException {
        write(ADDR_GPINTENB, GPINTENB);
    }

    public void writeDEFVALA() throws IOException {
        write(ADDR_DEFVALA, DEFVALA);
    }

    public void writeDEFVALB() throws IOException {
        write(ADDR_DEFVALB, DEFVALB);
    }

    public void writeINTCONA() throws IOException {
        write(ADDR_INTCONA, INTCONA);
    }

    public void writeINTCONB() throws IOException {
        write(ADDR_INTCONB, INTCONB);
    }

    public void writeGPPUA() throws IOException {
        write(ADDR_GPPUA, GPPUA);
    }

    public void writeGPPUB() throws IOException {
        write(ADDR_GPPUB, GPPUB);
    }

    public void writeOLATA() throws IOException {
        write(ADDR_OLATA, OLATA);
    }

    public void writeOLATB() throws IOException {
        write(ADDR_OLATB, OLATB);
    }

    private byte read(byte registerAddress) throws IOException {
        byte data;
        try {
            chipSelect.low();
            // The 0x00 byte is just arbitrary filler.
            data = spi.write(READ_OPCODE, registerAddress, (byte) 0x00)[2];
        } finally {
            chipSelect.high();
        }
        return data;
    }

    private byte uncheckedRead(byte registerAddress) {
        try {
            return read(registerAddress);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void handlePortAInterrupt() {
        callInterruptListeners(uncheckedRead(ADDR_INTFA), uncheckedRead(ADDR_INTCAPA), PORT_A_PINS);
    }

    private void handlePortBInterrupt() {
        callInterruptListeners(uncheckedRead(ADDR_INTFB), uncheckedRead(ADDR_INTCAPB), PORT_B_PINS);
    }

    private void callInterruptListeners(byte intf, byte intcap, Pin[] pins) {
        for (Pin pin : pins) {
            if (pin.getCorrespondingBit(intf)) {
                boolean capturedValue = pin.getCorrespondingBit(intcap);
                synchronized (globalListeners) {
                    for (InterruptListener listener : globalListeners) {
                        listener.onInterrupt(capturedValue, pin);
                    }
                }
                // This can in rare cases where the IO Extender is already configured create a PinView object before the
                // user indirectly creates it lazily...
                getPinView(pin).relayInterruptToListeners(capturedValue);
                break;
            }
        }
    }

    public static MCP23S17 newWithoutInterrupts(SpiChannel spiChannel,
                                                GpioPinDigitalOutput chipSelect)
            throws IOException {
        return new MCP23S17(
                spiChannel,
                chipSelect,
                null,
                null
        );
    }

    public static MCP23S17 newWithTiedInterrupts(SpiChannel spiChannel,
                                                 GpioPinDigitalOutput chipSelect,
                                                 GpioPinDigitalInput interrupt)
            throws IOException {
        MCP23S17 ioExpander = new MCP23S17(
                spiChannel,
                chipSelect,
                Objects.requireNonNull(interrupt, "interrupt must be non-null"),
                interrupt
        );
        // Set the IOCON.MIRROR bit to OR the INTA and INTB lines together.
        ioExpander.write(ADDR_IOCON, (byte) 0x40);
        attachInterruptOnLow(interrupt, () -> {
            ioExpander.handlePortAInterrupt();
            ioExpander.handlePortBInterrupt();
        });
        return ioExpander;
    }

    public static MCP23S17 newWithInterrupts(SpiChannel spiChannel,
                                             GpioPinDigitalOutput chipSelect,
                                             GpioPinDigitalInput portAInterrupt,
                                             GpioPinDigitalInput portBInterrupt)
            throws IOException {
        MCP23S17 ioExpander = new MCP23S17(
                spiChannel,
                chipSelect,
                Objects.requireNonNull(portAInterrupt, "portAInterrupt must be non-null"),
                Objects.requireNonNull(portBInterrupt, "portBInterrupt must be non-null")
        );
        attachInterruptOnLow(portAInterrupt, ioExpander::handlePortAInterrupt);
        attachInterruptOnLow(portBInterrupt, ioExpander::handlePortBInterrupt);
        return ioExpander;
    }

    public static MCP23S17 newWithPortAInterrupts(SpiChannel spiChannel,
                                                  GpioPinDigitalOutput chipSelect,
                                                  GpioPinDigitalInput portAInterrupt)
            throws IOException {
        MCP23S17 ioExpander = new MCP23S17(
                spiChannel,
                chipSelect,
                Objects.requireNonNull(portAInterrupt, "portAInterrupt must be non-null"),
                null
        );
        attachInterruptOnLow(portAInterrupt, ioExpander::handlePortAInterrupt);
        return ioExpander;
    }

    public static MCP23S17 newWithPortBInterrupts(SpiChannel spiChannel,
                                                  GpioPinDigitalOutput chipSelect,
                                                  GpioPinDigitalInput portBInterrupt)
            throws IOException {
        MCP23S17 ioExpander = new MCP23S17(
                spiChannel,
                chipSelect,
                null,
                Objects.requireNonNull(portBInterrupt, "portBInterrupt must be non-null")
        );
        attachInterruptOnLow(portBInterrupt, ioExpander::handlePortBInterrupt);
        return ioExpander;
    }

    private static void attachInterruptOnLow(GpioPinDigitalInput interrupt, Runnable callback) {
        interrupt.addListener(new GpioPinListenerDigital() {
            @Override
            public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {
                if (event.getState().isLow()) {
                    callback.run();
                }
            }
        });
    }
}
