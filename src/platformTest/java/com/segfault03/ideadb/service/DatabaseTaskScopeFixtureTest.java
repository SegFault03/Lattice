package com.segfault03.ideadb.service;

import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A database that has stopped answering must not take the IDE's UI thread with it. */
public class DatabaseTaskScopeFixtureTest extends BasePlatformTestCase {
    public void testCancellingOnTheUiThreadDoesNotWaitForAnUnresponsiveDatabase() throws Exception {
        CountDownLatch closing = new CountDownLatch(1), serverAnswers = new CountDownLatch(1), closed = new CountDownLatch(1);
        Connection unresponsive = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("close")) {
                closing.countDown();
                serverAnswers.await();
                closed.countDown();
            }
            return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
        });
        DatabaseTaskScope scope = new DatabaseTaskScope(Runnable::run);
        scope.parkIdleForTests(unresponsive);
        try {
            // Were the close to run on the UI thread, this would never return: serverAnswers is released only below.
            EdtTestUtil.runInEdtAndWait(scope::cancelPending);
            assertTrue("The connection is still closed, away from the UI thread", closing.await(10, TimeUnit.SECONDS));
            assertEquals(1, closed.getCount());
        } finally {
            serverAnswers.countDown();
        }
        assertTrue(closed.await(10, TimeUnit.SECONDS));
    }
}
