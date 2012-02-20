package de.jungblut.bsp.ft.annotation;

import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hama.bsp.BSP;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.sync.SyncException;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.TreeMap;

@SuppressWarnings("rawtypes")
class AnnotationBSP extends BSP {

    private int startSuperstep;
    private Object computationInstance;
    private final TreeMap<Integer, Method> executionOrder = new TreeMap<>();
    private Method haltMethod;

    @Override
    public void setup(BSPPeer peer) throws IOException, SyncException,
            InterruptedException {

        startSuperstep = peer.getConfiguration().getInt("attempt.superstep", 0);

        Method setupMethod = null;

        Class<?> computationClass;
        try {
            computationClass = peer.getConfiguration().getClassByName(
                    peer.getConfiguration().get("hama.superstep.class"));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        computationInstance = ReflectionUtils.newInstance(computationClass, conf);
        Method[] methods = computationClass.getDeclaredMethods();

        for (Method m : methods) {
            m.setAccessible(true);
            Annotation[] annotations = m.getDeclaredAnnotations();
            for (Annotation an : annotations) {
                if (an instanceof Setup) {
                    setupMethod = m;
                } else if (an instanceof Superstep) {
                    Superstep step = (Superstep) an;
                    executionOrder.put(step.value(), m);
                } else if (an instanceof HaltComputation) {
                    haltMethod = m;
                }
            }
        }

        // sanity check
        if (haltMethod == null) {
            throw new IllegalArgumentException(
                    "You have to define a method with the HaltComputation annotation!");
        } else {
            if (!haltMethod.getReturnType().equals(Boolean.class)) {
                throw new IllegalArgumentException(
                        "HaltComputation needs to return a boolean!");
            }
        }

        if (executionOrder.size() == 0) {
            throw new IllegalArgumentException(
                    "You have to provide at least a single superstep!");
        }

        if (setupMethod != null) {
            try {
                setupMethod.invoke(computationInstance, peer);
            } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException e) {
                e.printStackTrace();
            }
        }

    }

    @Override
    public void bsp(BSPPeer peer) throws IOException, SyncException,
            InterruptedException {

        int step = startSuperstep;
        while (true) {
            Method method = executionOrder.get(step);
            if (method != null) {
                try {
                    method.invoke(computationInstance, peer);
                    step++;
                } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException e) {
                    e.printStackTrace();
                }
            } else {
                step = 0;
            }
            try {
                if ((Boolean) haltMethod.invoke(computationInstance, peer)) {
                    break;
                }
            } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException e) {
                e.printStackTrace();
            }
        }

    }
}