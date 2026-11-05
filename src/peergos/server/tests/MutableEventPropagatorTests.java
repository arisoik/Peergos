package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.mutable.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;

import java.util.*;
import java.util.concurrent.*;

public class MutableEventPropagatorTests {

    /** Each listener has all of a commit's pointers before the next listener has any: the quota filter records what
     *  each pointer names before any of the commit's usage updates is queued, or the queue could judge one update
     *  before a child the commit moves under another of its writers is linked there */
    @Test
    public void eachListenerHasAllOfACommitsPointersBeforeTheNext() {
        MutablePointers accepting = (MutablePointers) java.lang.reflect.Proxy.newProxyInstance(MutablePointers.class.getClassLoader(),
                new Class<?>[] {MutablePointers.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setPointers"))
                        return CompletableFuture.completedFuture(true);
                    throw new UnsupportedOperationException(method.getName());
                });
        MutableEventPropagator propagator = new MutableEventPropagator(accepting);
        List<String> calls = new ArrayList<>();
        propagator.addListener(e -> calls.add("record " + e.writer));
        propagator.addListener(e -> calls.add("queue " + e.writer));

        Crypto crypto = Main.initCrypto();
        PublicKeyHash owner = key(crypto), a = key(crypto), b = key(crypto);
        propagator.setPointers(owner, List.of(new SignedPointerUpdate(a, new byte[] {1}), new SignedPointerUpdate(b, new byte[] {2}))).join();
        Assert.assertEquals(List.of("record " + a, "record " + b, "queue " + a, "queue " + b), calls);
    }

    private static PublicKeyHash key(Crypto crypto) {
        return ContentAddressedStorage.hashKey(SigningKeyPair.random(crypto.random, crypto.signer).publicSigningKey);
    }
}
