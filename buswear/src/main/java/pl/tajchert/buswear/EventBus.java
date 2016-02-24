/*
 * Copyright (C) 2015 Michal Tajchert (http://tajchert.pl), Polidea
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package pl.tajchert.buswear;

import android.content.Context;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

import pl.tajchert.buswear.wear.SendByteArrayToNode;
import pl.tajchert.buswear.wear.SendCommandToNode;
import pl.tajchert.buswear.wear.WearBusTools;

/**
 * EventBus is a central publish/subscribe event system for Android. Events are posted ({@link #postLocal(Object)}) to the
 * bus, which delivers it to subscribers that have a matching handler method for the event type. To receive events,
 * subscribers must register themselves to the bus using {@link #register(Object)}. Once registered,
 * subscribers receive events until {@link #unregister(Object)} is called. By convention, event handling methods must
 * be named "onEvent", be public, return nothing (void), and have exactly one parameter (the event).
 *
 *@author Michal Tajchert, Polidea
 * Author of EventBus (90% of that code) Markus Junginger, greenrobot
 */
public class EventBus {

    /** Log tag, apps may override it. */
    public static String TAG = "Event";

    static volatile EventBus defaultInstance;

    private static final EventBusBuilder DEFAULT_BUILDER = new EventBusBuilder();
    private static final Map<Class<?>, List<Class<?>>> eventTypesCache = new HashMap<Class<?>, List<Class<?>>>();

    private final Map<Class<?>, CopyOnWriteArrayList<Subscription>> subscriptionsByEventType;
    private final Map<Object, List<Class<?>>> typesBySubscriber;
    private final Map<Class<?>, Object> stickyEvents;
    private static ArrayList<Class<?>> classList = new ArrayList<>();

    private final ThreadLocal<PostingThreadState> currentPostingThreadState = new ThreadLocal<PostingThreadState>() {
        @Override
        protected PostingThreadState initialValue() {
            return new PostingThreadState();
        }
    };


    private final HandlerPoster mainThreadPoster;
    private final BackgroundPoster backgroundPoster;
    private final AsyncPoster asyncPoster;
    private final SubscriberMethodFinder subscriberMethodFinder;
    private final ExecutorService executorService;

    private final boolean throwSubscriberException;
    private final boolean logSubscriberExceptions;
    private final boolean logNoSubscriberMessages;
    private final boolean sendSubscriberExceptionEvent;
    private final boolean sendNoSubscriberEvent;
    private final boolean eventInheritance;


    /** Convenience singleton for apps using a process-wide EventBus instance. */
    public static EventBus getDefault() {
        if (defaultInstance == null) {
            synchronized (EventBus.class) {
                if (defaultInstance == null) {
                    defaultInstance = new EventBus();
                }
            }
        }
        return defaultInstance;
    }

    public static EventBusBuilder builder() {
        return new EventBusBuilder();
    }

    /** For unit test primarily. */
    public static void clearCaches() {
        SubscriberMethodFinder.clearCaches();
        eventTypesCache.clear();
    }

    /**
     * Creates a new EventBus instance; each instance is a separate scope in which events are delivered. To use a
     * central bus, consider {@link #getDefault()}.
     */
    public EventBus() {
        this(DEFAULT_BUILDER);
    }

    EventBus(EventBusBuilder builder) {
        subscriptionsByEventType = new HashMap<Class<?>, CopyOnWriteArrayList<Subscription>>();
        typesBySubscriber = new HashMap<Object, List<Class<?>>>();
        stickyEvents = new ConcurrentHashMap<Class<?>, Object>();
        mainThreadPoster = new HandlerPoster(this, Looper.getMainLooper(), 10);
        backgroundPoster = new BackgroundPoster(this);
        asyncPoster = new AsyncPoster(this);
        subscriberMethodFinder = new SubscriberMethodFinder(builder.skipMethodVerificationForClasses);
        logSubscriberExceptions = builder.logSubscriberExceptions;
        logNoSubscriberMessages = builder.logNoSubscriberMessages;
        sendSubscriberExceptionEvent = builder.sendSubscriberExceptionEvent;
        sendNoSubscriberEvent = builder.sendNoSubscriberEvent;
        throwSubscriberException = builder.throwSubscriberException;
        eventInheritance = builder.eventInheritance;
        executorService = builder.executorService;
    }


    /**
     * Registers the given subscriber to receive events. Subscribers must call {@link #unregister(Object)} once they
     * are no longer interested in receiving events.
     * <p/>
     * Subscribers have event handling methods that are identified by their name, typically called "onEvent". Event
     * handling methods must have exactly one parameter, the event. If the event handling method is to be called in a
     * specific thread, a modifier is appended to the method name. Valid modifiers match one of the {@link ThreadMode}
     * enums. For example, if a method is to be called in the UI/main thread by EventBus, it would be called
     * "onEventMainThread".
     */
    public void register(Object subscriber) {
        register(subscriber, false, 0);
    }

    /**
     * Like {@link #register(Object)} with an additional subscriber priority to influence the order of event delivery.
     * Within the same delivery thread ({@link ThreadMode}), higher priority subscribers will receive events before
     * others with a lower priority. The default priority is 0. Note: the priority does *NOT* affect the order of
     * delivery among subscribers with different {@link ThreadMode}s!
     */
    public void register(Object subscriber, int priority) {
        register(subscriber, false, priority);
    }

    /**
     * Like {@link #register(Object)}, but also triggers delivery of the most recent sticky event (posted with
     * {@link #postSticky(Parcelable, Context)}) to the given subscriber.
     */
    public void registerSticky(Object subscriber) {
        register(subscriber, true, 0);
    }

    /**
     * Like {@link #register(Object, int)}, but also triggers delivery of the most recent sticky event (posted with
     * {@link #postSticky(Parcelable, Context)}) to the given subscriber.
     */
    public void registerSticky(Object subscriber, int priority) {
        register(subscriber, true, priority);
    }

    private synchronized void register(Object subscriber, boolean sticky, int priority) {
        List<SubscriberMethod> subscriberMethods = subscriberMethodFinder.findSubscriberMethods(subscriber.getClass());
        for (SubscriberMethod subscriberMethod : subscriberMethods) {
            subscribe(subscriber, subscriberMethod, sticky, priority);
        }
    }

    // Must be called in synchronized block
    private void subscribe(Object subscriber, SubscriberMethod subscriberMethod, boolean sticky, int priority) {
        Class<?> eventType = subscriberMethod.eventType;
        if(!classList.contains(eventType)){
            classList.add(eventType);
        }
        CopyOnWriteArrayList<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        Subscription newSubscription = new Subscription(subscriber, subscriberMethod, priority);
        if (subscriptions == null) {
            subscriptions = new CopyOnWriteArrayList<Subscription>();
            subscriptionsByEventType.put(eventType, subscriptions);
        } else {
            if (subscriptions.contains(newSubscription)) {
                throw new EventBusException("Subscriber " + subscriber.getClass() + " already registered to event "
                        + eventType);
            }
        }

        // Starting with EventBus 2.2 we enforced methods to be public (might change with annotations again)
        // subscriberMethod.method.setAccessible(true);

        int size = subscriptions.size();
        for (int i = 0; i <= size; i++) {
            if (i == size || newSubscription.priority > subscriptions.get(i).priority) {
                subscriptions.add(i, newSubscription);
                break;
            }
        }

        List<Class<?>> subscribedEvents = typesBySubscriber.get(subscriber);
        if (subscribedEvents == null) {
            subscribedEvents = new ArrayList<Class<?>>();
            typesBySubscriber.put(subscriber, subscribedEvents);
        }
        subscribedEvents.add(eventType);

        if (sticky) {
            Object stickyEvent;
            synchronized (stickyEvents) {
                stickyEvent = stickyEvents.get(eventType);
            }
            if (stickyEvent != null) {
                // If the subscriber is trying to abort the event, it will fail (event is not tracked in posting state)
                // --> Strange corner case, which we don't take care of here.
                postToSubscription(newSubscription, stickyEvent, Looper.getMainLooper() == Looper.myLooper());
            }
        }
    }

    public synchronized boolean isRegistered(Object subscriber) {
        return typesBySubscriber.containsKey(subscriber);
    }

    /** Only updates subscriptionsByEventType, not typesBySubscriber! Caller must update typesBySubscriber. */
    private void unubscribeByEventType(Object subscriber, Class<?> eventType) {//TODO send command via Google Play Services
        List<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        if (subscriptions != null) {
            int size = subscriptions.size();
            for (int i = 0; i < size; i++) {
                Subscription subscription = subscriptions.get(i);
                if (subscription.subscriber == subscriber) {
                    subscription.active = false;
                    subscriptions.remove(i);
                    i--;
                    size--;
                }
            }
        }
    }

    /** Unregisters the given subscriber from all event classes. */
    public synchronized void unregister(Object subscriber) {//TODO send command via Google Play Services
        List<Class<?>> subscribedTypes = typesBySubscriber.get(subscriber);
        if (subscribedTypes != null) {
            for (Class<?> eventType : subscribedTypes) {
                unubscribeByEventType(subscriber, eventType);
            }
            typesBySubscriber.remove(subscriber);
        } else {
            Log.w(TAG, "Subscriber to unregister was not registered before: " + subscriber.getClass());
        }
    }

    /**
     * Posts the given event (object) to the event bus, also send it to connected Wear device
     * @param event needs to be Parcelable or Integer, Long, Float, Double, Short.
     * @param context for sending object
     */
    public void post(Object event, Context context) {
        PostingThreadState postingState = currentPostingThreadState.get();
        List<Object> eventQueue = postingState.eventQueue;
        eventQueue.add(event);

        if (!postingState.isPosting) {
            postingState.isMainThread = Looper.getMainLooper() == Looper.myLooper();
            postingState.isPosting = true;
            if (postingState.canceled) {
                throw new EventBusException("Internal error. Abort state was not reset");
            }
            try {
                while (!eventQueue.isEmpty()) {
                    Object obj = eventQueue.remove(0);
                    if(obj != null){
                        //Local stuff
                        postSingleEvent(obj, postingState);
                    }
                    //Try to parse it for sending and then send it
                    byte[] objectInArray = WearBusTools.parseToSend(obj);
                    if(obj != null && objectInArray != null) {
                        try {
                            new SendByteArrayToNode(objectInArray, obj.getClass(), context, false).start();
                        } catch (Exception e) {
                            if (logNoSubscriberMessages) {
                                Log.e(TAG, "Object cannot be send: " + e.getMessage());
                            }
                        }
                    }
                }
            } finally {
                postingState.isPosting = false;
                postingState.isMainThread = false;
            }
        }
    }

    /**
     * Posts the given event (object) to the local event bus
     * @param event any kind of Object, no restrictions.
     */
    public void postLocal(Object event) {
        PostingThreadState postingState = currentPostingThreadState.get();
        List<Object> eventQueue = postingState.eventQueue;
        eventQueue.add(event);

        if (!postingState.isPosting) {
            postingState.isMainThread = Looper.getMainLooper() == Looper.myLooper();
            postingState.isPosting = true;
            if (postingState.canceled) {
                throw new EventBusException("Internal error. Abort state was not reset");
            }
            try {
                while (!eventQueue.isEmpty()) {
                    postSingleEvent(eventQueue.remove(0), postingState);
                }
            } finally {
                postingState.isPosting = false;
                postingState.isMainThread = false;
            }
        }
    }

    /**
     * Posts the given event (object) to the event bus, also send it to connected Wear device
     * @param event needs to be Parcelable or Integer, Long, Float, Double, Short.
     * @param context for sending object
     */
    public void postRemote(Object event, Context context) {
        PostingThreadState postingState = currentPostingThreadState.get();
        List<Object> eventQueue = postingState.eventQueue;
        eventQueue.add(event);

        if (!postingState.isPosting) {
            postingState.isMainThread = Looper.getMainLooper() == Looper.myLooper();
            postingState.isPosting = true;
            if (postingState.canceled) {
                throw new EventBusException("Internal error. Abort state was not reset");
            }
            try {
                while (!eventQueue.isEmpty()) {
                    Object obj = eventQueue.remove(0);
                    //Try to parse it for sending and then send it
                    byte[] objectInArray = WearBusTools.parseToSend(obj);
                    if(obj != null && objectInArray != null) {
                        try {
                            new SendByteArrayToNode(objectInArray, obj.getClass(), context, false).start();
                        } catch (Exception e) {
                            if (logNoSubscriberMessages) {
                                Log.e(TAG, "Object cannot be send: " + e.getMessage());
                            }
                        }
                    }
                }
            } finally {
                postingState.isPosting = false;
                postingState.isMainThread = false;
            }
        }
    }
    /**
     * Posts the given event (object) to the event bus, also send it to connected Wear device
     * @param event needs to be Parcelable or Integer, Long, Float, Double, Short.
     * @param context for sending object
     * @param isSticky should event be cached
     */
    private void postRemote(Object event, Context context, boolean isSticky) {
        PostingThreadState postingState = currentPostingThreadState.get();
        List<Object> eventQueue = postingState.eventQueue;
        eventQueue.add(event);

        if (!postingState.isPosting) {
            postingState.isMainThread = Looper.getMainLooper() == Looper.myLooper();
            postingState.isPosting = true;
            if (postingState.canceled) {
                throw new EventBusException("Internal error. Abort state was not reset");
            }
            try {
                while (!eventQueue.isEmpty()) {
                    Object obj = eventQueue.remove(0);
                    //Try to parse it for sending and then send it
                    byte[] objectInArray = WearBusTools.parseToSend(obj);
                    if(obj != null && objectInArray != null) {
                        try {
                            new SendByteArrayToNode(objectInArray, obj.getClass(), context, isSticky).start();
                        } catch (Exception e) {
                            if (logNoSubscriberMessages) {
                                Log.e(TAG, "Object cannot be send: " + e.getMessage());
                            }
                        }
                    }
                }
            } finally {
                postingState.isPosting = false;
                postingState.isMainThread = false;
            }
        }
    }



    /**
     * Called from a subscriber's event handling method, further event delivery will be canceled. Subsequent
     * subscribers
     * won't receive the event. Events are usually canceled by higher priority subscribers (see
     * {@link #register(Object, int)}). Canceling is restricted to event handling methods running in posting thread
     * {@link ThreadMode#PostThread}.
     */
    public void cancelEventDelivery(Object event) {//TODO send command via Google Play Services
        PostingThreadState postingState = currentPostingThreadState.get();
        if (!postingState.isPosting) {
            throw new EventBusException(
                    "This method may only be called from inside event handling methods on the posting thread");
        } else if (event == null) {
            throw new EventBusException("Event may not be null");
        } else if (postingState.event != event) {
            throw new EventBusException("Only the currently handled event may be aborted");
        } else if (postingState.subscription.subscriberMethod.threadMode != ThreadMode.PostThread) {
            throw new EventBusException(" event handlers may only abort the incoming event");
        }

        postingState.canceled = true;
    }

    /**
     * Posts the given event to the event bus and holds on to the event (because it is sticky). The most recent sticky
     * event of an event's type is kept in memory for future access. This can be {@link #registerSticky(Object)} or
     * {@link #getStickyEvent(Class)}.
     */
    public void postSticky(Parcelable event, Context context) {
        synchronized (stickyEvents) {
            stickyEvents.put(event.getClass(), event);
        }
        // Should be posted after it is putted, in case the subscriber wants to remove immediately
        postLocal(event);
        postRemote(event, context, true);
    }

    public void postStickyRemote(Parcelable event, Context context) {
        synchronized (stickyEvents) {
            stickyEvents.put(event.getClass(), event);
        }
        // Should be posted after it is putted, in case the subscriber wants to remove immediately
        postRemote(event, context, true);
    }

    public void postStickyLocal(Object event) {
        synchronized (stickyEvents) {
            stickyEvents.put(event.getClass(), event);
        }
        // Should be posted after it is putted, in case the subscriber wants to remove immediately
        postLocal(event);
    }

    /**
     * Gets the most recent sticky event for the given type.
     *
     * @see #postSticky(android.os.Parcelable, Context)
     */
    public <T> T getStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.get(eventType));
        }
    }

    /**
     * Remove and gets the recent sticky event for the given event type, both on local and remote bus.
     *
     * @see #postSticky(Parcelable, Context)
     */
    public <T> T removeStickyEvent(Class<T> eventType, Context context) {
        removeStickyEventRemote(eventType, context);
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.remove(eventType));
        }
    }

    /**
     * Remove and gets the recent sticky event for the given event type, only on remote bus.
     *
     * @see #postSticky(Parcelable, Context)
     */
    public <T> void removeStickyEventRemote(Class<T> eventType, Context context) {
        new SendCommandToNode(WearBusTools.MESSAGE_PATH_COMMAND + "class.", null, eventType, context).start();
    }

    /**
     * Remove and gets the recent sticky event for the given event type, both on local and remote bus.
     *
     * @see #postSticky(Parcelable, Context)
     */
    public <T> T removeStickyEventLocal(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.remove(eventType));
        }
    }

    /**
     * Removes the sticky event if it equals to the given event, both on local and remote bus.
     *
     * @return true if the events matched and the sticky event was removed on local bus.
     */
    public boolean removeStickyEvent(Object event, Context context) {
        removeStickyEventRemote(event, context);
        synchronized (stickyEvents) {
            Class<?> eventType = event.getClass();
            Object existingEvent = stickyEvents.get(eventType);
            if (event.equals(existingEvent)) {
                stickyEvents.remove(eventType);
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Removes the sticky event if it equals to the given event, only on remote bus.
     */
    public void removeStickyEventRemote(Object event, Context context) {
        byte[] objectInArray = WearBusTools.parseToSend(event);
        if(event != null && objectInArray != null) {
            new SendCommandToNode(WearBusTools.MESSAGE_PATH_COMMAND + "event.", objectInArray, event.getClass(), context).start();
        }
    }

    /**
     * Removes the sticky event if it equals to the given event, only on local bus.
     *
     * @return true if the events matched and the sticky event was removed.
     */
    public boolean removeStickyEventLocal(Object event) {
        synchronized (stickyEvents) {
            Class<?> eventType = event.getClass();
            Object existingEvent = stickyEvents.get(eventType);
            if (event.equals(existingEvent)) {
                stickyEvents.remove(eventType);
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Removes all sticky events, both on a local and on a remote device.
     */
    public void removeAllStickyEvents(Context context) {
        removeAllStickyEventsRemote(context);
        synchronized (stickyEvents) {
            stickyEvents.clear();
        }
    }

    /**
     * Removes all sticky events, only on a remote device.
     */
    public void removeAllStickyEventsRemote(Context context) {
        new SendCommandToNode(WearBusTools.MESSAGE_PATH_COMMAND, WearBusTools.ACTION_STICKY_CLEAR_ALL.getBytes(), String.class, context).start();
    }

    /**
     * Removes all sticky events, only on a local device.
     */
    public void removeAllStickyEventsLocal() {
        synchronized (stickyEvents) {
            stickyEvents.clear();
        }
    }

    public boolean hasSubscriberForEvent(Class<?> eventClass) {
        List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
        if (eventTypes != null) {
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                CopyOnWriteArrayList<Subscription> subscriptions;
                synchronized (this) {
                    subscriptions = subscriptionsByEventType.get(clazz);
                }
                if (subscriptions != null && !subscriptions.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void postSingleEvent(Object event, PostingThreadState postingState) throws Error {
        Class<?> eventClass = event.getClass();
        boolean subscriptionFound = false;
        if (eventInheritance) {
            List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                subscriptionFound |= postSingleEventForEventType(event, postingState, clazz);
            }
        } else {
            subscriptionFound = postSingleEventForEventType(event, postingState, eventClass);
        }
        if (!subscriptionFound && !(event instanceof NoSubscriberEvent)) {
            if (logNoSubscriberMessages) {
                Log.d(TAG, "No local subscribers registered for event " + eventClass);
            }
            if (sendNoSubscriberEvent && eventClass != NoSubscriberEvent.class &&
                    eventClass != SubscriberExceptionEvent.class) {
                postLocal(new NoSubscriberEvent(this, event));
            }
        }
    }

    private boolean postSingleEventForEventType(Object event, PostingThreadState postingState, Class<?> eventClass) {
        CopyOnWriteArrayList<Subscription> subscriptions;
        synchronized (this) {
            subscriptions = subscriptionsByEventType.get(eventClass);
        }
        if (subscriptions != null && !subscriptions.isEmpty()) {
            for (Subscription subscription : subscriptions) {
                postingState.event = event;
                postingState.subscription = subscription;
                boolean aborted = false;
                try {
                    postToSubscription(subscription, event, postingState.isMainThread);
                    aborted = postingState.canceled;
                } finally {
                    postingState.event = null;
                    postingState.subscription = null;
                    postingState.canceled = false;
                }
                if (aborted) {
                    break;
                }
            }
            return true;
        }
        return false;
    }

    private void postToSubscription(Subscription subscription, Object event, boolean isMainThread) {
        switch (subscription.subscriberMethod.threadMode) {
            case PostThread:
                invokeSubscriber(subscription, event);
                break;
            case MainThread:
                if (isMainThread) {
                    invokeSubscriber(subscription, event);
                } else {
                    mainThreadPoster.enqueue(subscription, event);
                }
                break;
            case BackgroundThread:
                if (isMainThread) {
                    backgroundPoster.enqueue(subscription, event);
                } else {
                    invokeSubscriber(subscription, event);
                }
                break;
            case Async:
                asyncPoster.enqueue(subscription, event);
                break;
            default:
                throw new IllegalStateException("Unknown thread mode: " + subscription.subscriberMethod.threadMode);
        }
    }

    /** Looks up all Class objects including super classes and interfaces. Should also work for interfaces. */
    private List<Class<?>> lookupAllEventTypes(Class<?> eventClass) {
        synchronized (eventTypesCache) {
            List<Class<?>> eventTypes = eventTypesCache.get(eventClass);
            if (eventTypes == null) {
                eventTypes = new ArrayList<Class<?>>();
                Class<?> clazz = eventClass;
                while (clazz != null) {
                    eventTypes.add(clazz);
                    addInterfaces(eventTypes, clazz.getInterfaces());
                    clazz = clazz.getSuperclass();
                }
                eventTypesCache.put(eventClass, eventTypes);
            }
            return eventTypes;
        }
    }

    /** Recurses through super interfaces. */
    static void addInterfaces(List<Class<?>> eventTypes, Class<?>[] interfaces) {
        for (Class<?> interfaceClass : interfaces) {
            if (!eventTypes.contains(interfaceClass)) {
                eventTypes.add(interfaceClass);
                addInterfaces(eventTypes, interfaceClass.getInterfaces());
            }
        }
    }

    /**
     * Invokes the subscriber if the subscriptions is still active. Skipping subscriptions prevents race conditions
     * between {@link #unregister(Object)} and event delivery. Otherwise the event might be delivered after the
     * subscriber unregistered. This is particularly important for main thread delivery and registrations bound to the
     * live cycle of an Activity or Fragment.
     */
    void invokeSubscriber(PendingPost pendingPost) {
        Object event = pendingPost.event;
        Subscription subscription = pendingPost.subscription;
        PendingPost.releasePendingPost(pendingPost);
        if (subscription.active) {
            invokeSubscriber(subscription, event);
        }
    }

    void invokeSubscriber(Subscription subscription, Object event) {
        try {
            subscription.subscriberMethod.method.invoke(subscription.subscriber, event);
        } catch (InvocationTargetException e) {
            handleSubscriberException(subscription, event, e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unexpected exception", e);
        }
    }

    private void handleSubscriberException(Subscription subscription, Object event, Throwable cause) {
        if (event instanceof SubscriberExceptionEvent) {
            if (logSubscriberExceptions) {
                // Don't send another SubscriberExceptionEvent to avoid infinite event recursion, just log
                Log.e(TAG, "SubscriberExceptionEvent subscriber " + subscription.subscriber.getClass()
                        + " threw an exception", cause);
                SubscriberExceptionEvent exEvent = (SubscriberExceptionEvent) event;
                Log.e(TAG, "Initial event " + exEvent.causingEvent + " caused exception in "
                        + exEvent.causingSubscriber, exEvent.throwable);
            }
        } else {
            if (throwSubscriberException) {
                throw new EventBusException("Invoking subscriber failed", cause);
            }
            if (logSubscriberExceptions) {
                Log.e(TAG, "Could not dispatch event: " + event.getClass() + " to subscribing class "
                        + subscription.subscriber.getClass(), cause);
            }
            if (sendSubscriberExceptionEvent) {
                SubscriberExceptionEvent exEvent = new SubscriberExceptionEvent(this, cause, event,
                        subscription.subscriber);
                postLocal(exEvent);
            }
        }
    }

    /** For ThreadLocal, much faster to set (and get multiple values). */
    final static class PostingThreadState {
        final ArrayList<Object> eventQueue = new ArrayList<Object>();
        boolean isPosting;
        boolean isMainThread;
        Subscription subscription;
        Object event;
        boolean canceled;
    }

    ExecutorService getExecutorService() {
        return executorService;
    }

    // Just an idea: we could provide a callback to post() to be notified, an alternative would be events, of course...
    /* public */interface PostCallback {
        void onPostCompleted(List<SubscriberExceptionEvent> exceptionEvents);
    }

    /** Unparcelable object and post it to local bus to keep it in the "pipe"  */
    public static void syncEvent(MessageEvent messageEvent){
        //catch events
        byte [] objectArray = messageEvent.getData();
        if(messageEvent.getPath().contains(WearBusTools.MESSAGE_PATH)) {
            String className =  messageEvent.getPath().substring(messageEvent.getPath().lastIndexOf(".") + 1);
            //Try simple types (String, Integer, Long...)
            Object obj = WearBusTools.getSendSimpleObject(objectArray, className);
            if(obj == null) {
                //Find corresponding parcel for particular object in local receivers
                obj = findParcel(objectArray, className);
            }

            if(obj != null){
                //send them to local bus
                EventBus.getDefault().postLocal(obj);
            }
        } else if(messageEvent.getPath().contains(WearBusTools.MESSAGE_PATH_STICKY)) {
            //Catch sticky events
            String className =  messageEvent.getPath().substring(messageEvent.getPath().lastIndexOf(".") + 1);
            //Try simple types (String, Integer, Long...)
            Object obj = WearBusTools.getSendSimpleObject(objectArray, className);
            if(obj == null) {
                //Find corresponding parcel for particular object in local receivers
                obj = findParcel(objectArray, className);
            }

            if(obj != null){
                //send them to local bus
                EventBus.getDefault().postStickyLocal(obj);
            }
        } else if(messageEvent.getPath().contains(WearBusTools.MESSAGE_PATH_COMMAND)){
            //Commands used for managing sticky events.
            stickyEventCommand(messageEvent, objectArray);
        }
    }

    /**
     * Method used to find which command and if class/object is needed to retrieve it and call local method.
     */
    private static void stickyEventCommand(MessageEvent messageEvent, byte[] objectArray) {
        String className =  messageEvent.getPath().substring(messageEvent.getPath().lastIndexOf(".") + 1);
        if(className.equals("String")){
            String action = new String(objectArray);
            Log.d(TAG, "syncEvent action: " + action);
            if(action.equals(WearBusTools.ACTION_STICKY_CLEAR_ALL)){
                getDefault().removeAllStickyEventsLocal();
            } else {
                //Even if it was String it should be removeStickyEventLocal instead of all, it is due to fact that action key is send as a String.
                for (Class classTmp : classList) {
                    if (className.equals(classTmp.getSimpleName())) {
                        getDefault().removeStickyEventLocal(classTmp);
                    }
                }
            }
        } else {
            int dotPlace = messageEvent.getPath().lastIndexOf(".");
            String typeOfRemove = messageEvent.getPath().substring(dotPlace - 5, dotPlace);
            if(typeOfRemove.equals("class")){
                //Call removeStickyEventLocal so first retrieve class that needs to be removed.
                for (Class classTmp : classList) {
                    if (className.equals(classTmp.getSimpleName())) {
                        getDefault().removeStickyEventLocal(classTmp);
                    }
                }
            } else {
                //Call removeStickyEventLocal so first retrieve object that needs to be removed.
                Object obj = WearBusTools.getSendSimpleObject(objectArray, className);
                if(obj == null) {
                    //Find corresponding parcel for particular object in local receivers
                    obj = findParcel(objectArray, className);
                }
                if(obj != null){
                    getDefault().removeStickyEventLocal(obj);
                }
            }
        }
    }

    private static Object findParcel(byte[] objectArray, String className) {
        for (Class classTmp : classList) {
            if (className.equals(classTmp.getSimpleName())) {
                try {
                    Constructor declaredConstructor = classTmp.getDeclaredConstructor(Parcel.class);
                    declaredConstructor.setAccessible(true);
                    return declaredConstructor.newInstance(WearBusTools.byteToParcel(objectArray));
                } catch (Exception e) {
                    Log.d(WearBusTools.BUSWEAR_TAG, "syncEvent error: " + e.getMessage());
                }
            }
        }
        return null;
    }
}
