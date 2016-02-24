package pl.tajchert.buswear.wear;

import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.util.Log;

import org.greenrobot.eventbus.NoSubscriberEvent;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

public class WearBusTools {

    public final static String BUSWEAR_TAG = "BusWearTag";
    public final static String MESSAGE_PATH = "pl.tajchert.buswear.event.";
    public final static String MESSAGE_PATH_STICKY = "pl.tajchert.buswear.stickyevent.";
    public final static String MESSAGE_PATH_COMMAND = "pl.tajchert.buswear.command.";

    //Commands
    public final static String ACTION_STICKY_CLEAR_ALL = "pl.tajchert.buswear.clearall";

    //Other commands to remove object of some class of particular one, are created by adding "class." or "event." dynamically to MESSAGE_PATH_COMMAND
    public final static String PREFIX_CLASS = "class.";
    public final static String PREFIX_EVENT = "event.";

    public final static String CLASS_NAME_DELIMITER = "-";

    public static final long CONNECTION_TIME_OUT_MS = 100;

    /**
     * Converts the Parcelable object to a byte[]
     * @param parcelable
     * @return
     */
    public static byte[] parcelToByte(@NonNull Parcelable parcelable) {
        Parcel parcel = Parcel.obtain();
        parcelable.writeToParcel(parcel, 0);
        byte[] bytes = parcel.marshall();
        parcel.recycle();
        return bytes;
    }

    /**
     * Converts the byte[] to a Parcel
     * @param bytes
     * @return
     */
    public static Parcel byteToParcel(@NonNull byte[] bytes) {
        Parcel parcel = Parcel.obtain();
        parcel.unmarshall(bytes, 0, bytes.length);
        parcel.setDataPosition(0);
        return parcel;
    }

    /**
     * Internal BusWear method, using it outside of library is not supported or tested.
     * Recreates received object using classname and byte[]
     */
    public static Object getSendSimpleObject(byte[] objectArray, String className) {
        Object obj = null;
        if (className.equals("String")) {
            try {
                obj = new String(objectArray, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                Log.d(WearBusTools.BUSWEAR_TAG, "syncEvent, cannot unparse event as: " + e.getMessage());
            }
        } else if (className.equals("Integer")) {
            obj = ByteBuffer.wrap(objectArray).getInt();
        } else if (className.equals("Long")) {
            obj = ByteBuffer.wrap(objectArray).getLong();
        } else if (className.equals("Double")) {
            obj = ByteBuffer.wrap(objectArray).getDouble();
        } else if (className.equals("Float")) {
            obj = ByteBuffer.wrap(objectArray).getFloat();
        } else if (className.equals("Short")) {
            obj = ByteBuffer.wrap(objectArray).getShort();
        }
        return obj;
    }

    /**
     * Internal BusWear method, using it outside of library is not supported or tested.
     * Method used for parsing known objects or Parcelable one to byte[],
     * some classes are not implemented (ex. Boolean) and most likely shouldn't be
     *
     * @param obj
     * @return
     */
    public static byte[] parseToSend(Object obj) {
        if (obj instanceof NoSubscriberEvent) {
            return null;
        }
        byte[] objArray;
        if (obj instanceof String) {
            try {
                objArray = ((String) obj).getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                objArray = ((String) obj).getBytes();
            }
        } else if (obj instanceof Integer) {
            objArray = ByteBuffer.allocate(4).putInt((Integer) obj).array();
        } else if (obj instanceof Long) {
            objArray = ByteBuffer.allocate(8).putLong((Long) obj).array();
        } else if (obj instanceof Float) {
            objArray = ByteBuffer.allocate(4).putFloat((Float) obj).array();
        } else if (obj instanceof Double) {
            objArray = ByteBuffer.allocate(8).putDouble((Double) obj).array();
        } else if (obj instanceof Short) {
            objArray = ByteBuffer.allocate(2).putShort((Short) obj).array();
        } else if (obj instanceof Parcelable) {
            objArray = WearBusTools.parcelToByte((Parcelable) obj);
        } else {
            throw new RuntimeException("Object needs to be Parcelable or Integer, Long, Float, Double, Short.");
        }
        return objArray;
    }
}
