//
//  ShareViewController.swift
//  Klardrop Share
//
//  Created by Carlo Marinangeli on 29/07/2023.
//  Copyright © 2023 orgName. All rights reserved.
//

import UIKit
import Social

//class ShareViewController: SLComposeServiceViewController {
//
//    override func isContentValid() -> Bool {
//        // Do validation of contentText and/or NSExtensionContext attachments here
//        return true
//    }
//
//    override func didSelectPost() {
//        // This is called after the user selects Post. Do the upload of contentText and/or NSExtensionContext attachments.
//
//        // Inform the host that we're done, so it un-blocks its UI. Note: Alternatively you could call super's -didSelectPost, which will similarly complete the extension context.
//        self.extensionContext!.completeRequest(returningItems: [], completionHandler: nil)
//    }
//
//    override func configurationItems() -> [Any]! {
//        // To add configuration options via table cells at the bottom of the sheet, return an array of SLComposeSheetConfigurationItem here.
//        return []
//    }
//
//}

import UIKit
import MobileCoreServices

@objc(ShareExtensionViewController)
class ShareViewController: UIViewController {

  override func viewDidLoad() {
    super.viewDidLoad()
    
    self.handleSharedFile()
  }
  
    private func handleSharedFile() {
      // extracting the path to the URL that is being shared
      let attachments = (self.extensionContext?.inputItems.first as? NSExtensionItem)?.attachments ?? []
      let contentType = kUTTypeData as String
      for provider in attachments {
        // Check if the content type is the same as we expected
        if provider.hasItemConformingToTypeIdentifier(contentType) {
          provider.loadItem(forTypeIdentifier: contentType,
                            options: nil) { [unowned self] (data, error) in
          // Handle the error here if you want
          guard error == nil else { return }
               
          if let url = data as? URL,
             let imageData = try? Data(contentsOf: url) {
               self.save(imageData, key: "imageData", value: imageData)
          } else {
            // Handle this situation as you prefer
            fatalError("Impossible to save image")
          }
        }}
      }
    }
      
    private func save(_ data: Data, key: String, value: Any) {
      // You must use the userdefaults of an app group, otherwise the main app don't have access to it.
        let userDefaults = UserDefaults.standard
      userDefaults.set(data, forKey: key)
    }
}
