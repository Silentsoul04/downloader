// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "remoting/host/it2me/it2me_confirmation_dialog_proxy.h"

#include <memory>

#include "base/bind.h"
#include "base/memory/ref_counted.h"
#include "base/run_loop.h"
#include "base/single_thread_task_runner.h"
#include "base/threading/thread.h"
#include "testing/gmock/include/gmock/gmock.h"
#include "testing/gmock_mutant.h"
#include "testing/gtest/include/gtest/gtest.h"

using ::testing::InvokeWithoutArgs;
using ::testing::CreateFunctor;

namespace remoting {

class StubIt2MeConfirmationDialog : public It2MeConfirmationDialog {
 public:
  explicit StubIt2MeConfirmationDialog(
      scoped_refptr<base::SingleThreadTaskRunner> task_runner)
      : task_runner_(task_runner) {
  }
  ~StubIt2MeConfirmationDialog() override {
    EXPECT_TRUE(task_runner_->BelongsToCurrentThread());
  }

  void ReportResult(Result result) {
    ASSERT_TRUE(task_runner_->BelongsToCurrentThread());
    callback_.Run(result);
  }

  MOCK_METHOD0(OnShow, void());

  // It2MeConfirmationDialog implementation.
  void Show(const ResultCallback& callback) override {
    EXPECT_TRUE(callback_.is_null());
    EXPECT_TRUE(task_runner_->BelongsToCurrentThread());
    callback_ = callback;
    OnShow();
  }

 private:
  scoped_refptr<base::SingleThreadTaskRunner> task_runner_;
  ResultCallback callback_;
};

// Encapsulates a target for It2MeConfirmationDialog::ResultCallback.
class ResultCallbackTarget {
 public:
  explicit ResultCallbackTarget(
      scoped_refptr<base::SingleThreadTaskRunner> task_runner)
      : task_runner_(task_runner) {
  }

  MOCK_METHOD1(OnDialogResult, void(It2MeConfirmationDialog::Result));

  It2MeConfirmationDialog::ResultCallback MakeCallback() {
    return base::Bind(&ResultCallbackTarget::HandleDialogResult,
                      base::Unretained(this));
  }

 private:
  void HandleDialogResult(It2MeConfirmationDialog::Result result) {
    EXPECT_TRUE(task_runner_->BelongsToCurrentThread());
    OnDialogResult(result);
  }

  scoped_refptr<base::SingleThreadTaskRunner> task_runner_;
};

class It2MeConfirmationDialogProxyTest : public testing::Test {
 public:
  It2MeConfirmationDialogProxyTest();
  ~It2MeConfirmationDialogProxyTest() override;

  scoped_refptr<base::SingleThreadTaskRunner> main_task_runner() {
    return message_loop_.task_runner();
  }

  scoped_refptr<base::SingleThreadTaskRunner> dialog_task_runner() {
    return dialog_thread_.task_runner();
  }

  void Run() {
    run_loop_.Run();
  }

  void Quit() {
    run_loop_.Quit();
  }

  It2MeConfirmationDialogProxy* dialog_proxy() {
    return dialog_proxy_.get();
  }

  StubIt2MeConfirmationDialog* dialog() {
    return dialog_;
  }

 private:
  base::MessageLoop message_loop_;
  base::RunLoop run_loop_;
  base::Thread dialog_thread_;

  // |dialog_| is owned by |dialog_proxy_| but we keep an alias for test
  // purposes.
  StubIt2MeConfirmationDialog* dialog_;
  std::unique_ptr<It2MeConfirmationDialogProxy> dialog_proxy_;
};

It2MeConfirmationDialogProxyTest::It2MeConfirmationDialogProxyTest()
    : dialog_thread_("test dialog thread") {
  dialog_thread_.Start();

  dialog_ = new StubIt2MeConfirmationDialog(dialog_task_runner());
  dialog_proxy_.reset(new It2MeConfirmationDialogProxy(
      dialog_task_runner(), std::unique_ptr<It2MeConfirmationDialog>(dialog_)));
}

It2MeConfirmationDialogProxyTest::~It2MeConfirmationDialogProxyTest() {}

TEST_F(It2MeConfirmationDialogProxyTest, Show) {
  ResultCallbackTarget callback_target(main_task_runner());

  EXPECT_CALL(*dialog(), OnShow())
      .WillOnce(
          InvokeWithoutArgs(
              CreateFunctor(
                  &StubIt2MeConfirmationDialog::ReportResult,
                  base::Unretained(dialog()),
                  It2MeConfirmationDialog::Result::CANCEL)));

  EXPECT_CALL(callback_target,
              OnDialogResult(It2MeConfirmationDialog::Result::CANCEL))
      .WillOnce(
          InvokeWithoutArgs(this, &It2MeConfirmationDialogProxyTest::Quit));

  dialog_proxy()->Show(callback_target.MakeCallback());

  Run();
}

}  // namespace remoting
