package g2lib;

import org.usb4java.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.TreeMap;

public class Usb {
    public static final int VENDOR_ID = 0xffc;
    public static final int PRODUCT_ID = 2;
    public static final int IFACE = 0;
    public static final Map<Integer, String> ERRORS = errorMap();

    private final Context context;
    private final Device device;
    private final DeviceHandle handle;

    public Usb(Context context, Device device, DeviceHandle handle) {
        this.context = context;
        this.device = device;
        this.handle = handle;
    }

    private static Map<Integer, String> errorMap() {
        TreeMap<Integer, String> m = new TreeMap<>();
        m.put(-3, "ERROR_ACCESS");
        m.put(-6, "ERROR_BUSY");
        m.put(14, "ERROR_COUNT");
        m.put(-10, "ERROR_INTERRUPTED");
        m.put(-2, "ERROR_INVALID_PARAM");
        m.put(-1, "ERROR_IO");
        m.put(-4, "ERROR_NO_DEVICE");
        m.put(-11, "ERROR_NO_MEM");
        m.put(-5, "ERROR_NOT_FOUND");
        m.put(-12, "ERROR_NOT_SUPPORTED");
        m.put(-99, "ERROR_OTHER");
        m.put(-8, "ERROR_OVERFLOW");
        m.put(-9, "ERROR_PIPE");
        m.put(-7, "ERROR_TIMEOUT");
        return m;
    }

    static void retcode(int result, String msg) {
        if (result < 0) {
            throw new LibUsbException(msg + ": " + ERRORS.get(result), result);
        }
    }

    /**
     * only supports 1 connected device
     */
    static Device getG2Device(Context context) {
        // Read the USB device list
        final DeviceList list = new DeviceList();

        retcode(LibUsb.getDeviceList(context, list), "Unable to get device list");

        // Iterate over all devices and dump them
        for (Device device : list) {
            final DeviceDescriptor descriptor = new DeviceDescriptor();
            retcode(LibUsb.getDeviceDescriptor(device, descriptor), "Unable to read device descriptor");
            if (descriptor.idVendor() == VENDOR_ID && descriptor.idProduct() == PRODUCT_ID) {
                dumpDevice(device);
                return device;
            } else {
                LibUsb.unrefDevice(device);
            }
        }
        return null;
    }

    public static Usb initialize() {
        // Create the libusb context
        final Context context = new Context();

        // Initialize the libusb context
        Usb.retcode(LibUsb.init(context),"Unable to initialize libusb");

        Device device = Usb.getG2Device(context);
        if (device == null) {
            throw new RuntimeException("No G2 device found");
        }

        DeviceHandle handle = new DeviceHandle();
        Usb.retcode(LibUsb.open(device, handle), "Unable to acquire handle");

        Usb.retcode(LibUsb.claimInterface(handle, Usb.IFACE), "Unable to claim interface");

        return new Usb(context,device,handle);
    }

    public void shutdown() {

        Usb.retcode(LibUsb.releaseInterface(handle, Usb.IFACE), "Unable to release interface");

        LibUsb.close(handle);

        LibUsb.unrefDevice(device);

        // Deinitialize the libusb context
        LibUsb.exit(context);
    }

    public int sendBulk(String msg, byte[] data) {
        System.out.printf("--------------- Send Bulk: %s ----------------\n", msg);

        int size = data.length + 4;
        ByteBuffer buffer = BufferUtils.allocateByteBuffer(size);
        buffer.put((byte) (size / 256));
        buffer.put((byte) (size % 256));
        buffer.put(data);
        int crc = CRC16.crc16(data, 0, data.length);
        //dumpBytes(data);
        System.out.printf("send crc: %x %x %x\n", crc, crc / 256, crc % 256);
        buffer.put((byte) (crc / 256));
        buffer.put((byte) (crc % 256));
        Util.dumpBuffer(buffer);
        IntBuffer transferred = BufferUtils.allocateIntBuffer();
        int r = LibUsb.bulkTransfer(handle, (byte) 0x03, buffer, transferred, 10000);
        if (r >= 0) {
            //transferred.rewind();
            System.out.println("Sent: " + transferred.get(0));
        }
        return transferred.get();
    }

    public int readInterrupt() {
        System.out.println("--------------- Read Interrupt ----------------");
        ByteBuffer buffer = BufferUtils.allocateByteBuffer(16);
        IntBuffer transferred = BufferUtils.allocateIntBuffer();
        int r = LibUsb.interruptTransfer(handle, (byte) 0x81, buffer, transferred, 2000);
        if (r < 0) {
            System.out.println("interrupt failure: " + ERRORS.get(r));
            return r;
        } else {
            System.out.println("interrupt success: " + transferred.get());
            Util.dumpBuffer(buffer);
            int type = buffer.get(0) & 0xf;
            boolean extended = type == 1;
            boolean embedded = type == 2;
            if (embedded) {
                int dil = (buffer.get(0) & 0xf0) >> 4;
                int crc = CRC16.crc16(buffer, 1, dil - 2);
                System.out.printf("embedded, crc: %x %x\n", crc, buffer.position(dil - 1).getShort());
            }
            int size = buffer.position(1).getShort();
            if (extended) {
                System.out.printf("extended, size: %x\n", size);
            }
            return size;
        }
    }

    public ByteBuffer readBulkRetries(int size, int retries) {
        System.out.println("---------------- Read Bulk ---------------");
        for (int i = 0; i < retries; i++) {
            ByteBuffer r = readBulk(size);
            if (r != null) {
                return r;
            }
            System.out.println("Retrying ...");
        }
        return null;
    }

    public ByteBuffer readBulk(int size) {
        ByteBuffer buffer = BufferUtils.allocateByteBuffer(size);
        IntBuffer transferred = BufferUtils.allocateIntBuffer();
        int r = LibUsb.bulkTransfer(handle, (byte) 0x82, buffer, transferred, 5000);
        if (r < 0) {
            System.out.println("bulk read failed: " + ERRORS.get(r));
            return null;
        } else {
            int tfrd = transferred.get();
            if (tfrd > 0) {
                System.out.printf("bulk read success: %x\n", tfrd);
                // buffer.rewind();
                int len = buffer.limit();
                System.out.printf("Recd %x:\n", len);
                //dumpBytes(recd);
                Util.dumpBuffer(buffer);
                int ecrc = CRC16.crc16(buffer, 0, len - 2);
                System.out.printf("crc: %x %x\n", ecrc, buffer.position(len - 2).getShort());
                return buffer;
            } else {
                System.out.println("Nothing read.");
                return null;
            }
        }
    }

    public int sendCmdRequest(String msg, int... cdata) {
        byte[] data = new byte[cdata.length + 3];
        data[0] = (byte) 0x01;
        data[1] = (byte) (0x20 + 0x0c); // CMD_REQ + CMD_SYS
        data[2] = (byte) 0x41;
        for (int i = 0; i < cdata.length; i++) {
            data[i + 3] = (byte) cdata[i];
        }
        return sendBulk(msg, data);
    }

    public ByteBuffer readExtended() {
        int size = readInterrupt();
        if (size > 0) {

            return readBulkRetries(size, 5);

        } else {
            return null;
        }
    }

    /**
     * Dumps the specified device to stdout.
     *
     * @param device The device to dump.
     */
    public static void dumpDevice(final Device device) {
        // Dump device address and bus number
        final int address = LibUsb.getDeviceAddress(device);
        final int busNumber = LibUsb.getBusNumber(device);
        System.out.printf("Device %03d/%03d%n", busNumber, address);

        // Dump port number if available
        final int portNumber = LibUsb.getPortNumber(device);
        if (portNumber != 0)
            System.out.println("Connected to port: " + portNumber);

        // Dump parent device if available
        final Device parent = LibUsb.getParent(device);
        if (parent != null) {
            final int parentAddress = LibUsb.getDeviceAddress(parent);
            final int parentBusNumber = LibUsb.getBusNumber(parent);
            System.out.printf("Parent: %03d/%03d%n",
                    parentBusNumber, parentAddress);
        }

        // Dump the device speed
        System.out.println("Speed: "
                + DescriptorUtils.getSpeedName(LibUsb.getDeviceSpeed(device)));

        // Read the device descriptor
        final DeviceDescriptor descriptor = new DeviceDescriptor();
        retcode(LibUsb.getDeviceDescriptor(device, descriptor), "Unable to read device descriptor");

        // Try to open the device. This may fail because user has no
        // permission to communicate with the device. This is not
        // important for the dumps, we are just not able to resolve string
        // descriptor numbers to strings in the descriptor dumps.
        DeviceHandle handle = new DeviceHandle();
        retcode(LibUsb.open(device, handle), "Unable to open device");


        // Dump the device descriptor
        System.out.print(descriptor.dump(handle));

        // Dump all configuration descriptors
        dumpConfigurationDescriptors(device, descriptor.bNumConfigurations());

        // Close the device if it was opened
        LibUsb.close(handle);

    }

    public static void dumpConfigurationDescriptors(final Device device,
                                                    final int numConfigurations) {
        for (byte i = 0; i < numConfigurations; i += 1) {
            final ConfigDescriptor descriptor = new ConfigDescriptor();
            retcode(LibUsb.getConfigDescriptor(device, i, descriptor), "Unable to read config descriptor");
            try {
                System.out.println(descriptor.dump().replaceAll("(?m)^",
                        "  "));
            } finally {
                // Ensure that the config descriptor is freed
                LibUsb.freeConfigDescriptor(descriptor);
            }
        }
    }
}
