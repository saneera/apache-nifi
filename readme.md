Registry - a subproject of Apache NiFi - is a complementary application that provides a central location for storage and management of shared resources across one or more instances of NiFi or MiNiFi.

Apache NiFi Registry provides the following features:

Implementation of a Flow Registry for storing and managing versioned flows
Integration with NiFi to allow storing, retrieving, and upgrading versioned flows from a Flow Registry
Administration of the Registry for defining users, groups, and policies


I Started NiFi Registry. Now What?
Now that NiFi Registry has been started, we can bring up the User Interface (UI). To get started, open a web browser and navigate to http://localhost:18080/nifi-registry. The port can be changed by editing the nifi-registry.properties file in the NiFi Registry conf directory, but the default port is 18080.

This will bring up the Registry UI, which at this point is empty as there are no flow resources available to share yet:

Empty Registry

Create a Bucket
A bucket is needed in our registry to store and organize NiFi dataflows. To create one, select the Settings icon (Settings Icon)in the top right corner of the screen. In the Buckets window, select the "New Bucket" button.

New Bucket
Enter the bucket name "Test" and select the "Create" button.

Test Bucket Dialog
The "Test" bucket is created:

Test Bucket

There are no permissions configured by default, so anyone is able to view, create and modify buckets in this instance. For information on securing the system, see the System Administrator’s Guide.

Connect NiFi to the Registry
Now it is time to tell NiFi about the local registry instance.

Start a NiFi instance if one isn’t already running and bring up the UI. Go to controller settings from the top-right menu:

Global Menu - Controller Settings
Select the Registry Clients tab and add a new Registry Client giving it a name and selecting a type:

Add Registry Client Dialog
Click "Add".

Registry Client Added
Once a Registry Client has been added, configure it by clicking the "Edit" button (Edit Button) in the far-right column. In the Edit Registry Client window, select the Properties tab and enter a URL of http://localhost:18080:

Configure Registry Client Properties
Click "Update" to save the configuration and close the window:

Local Registry Client
Start Version Control on a Process Group
With NiFi connected to a NiFi Registry, dataflows can be version controlled on the process group level.

Right-click on a process group and select "Version→Start version control" from the context menu:

ABCD Process Group Menu
The local registry instance and "Test" bucket are chosen by default to store your flow since they are the only registry connected and bucket available. Enter a flow name, flow description, comments and select "Save":

Initial Save of ABCD Flow
As indicated by the Version State icon (Up To Date Icon) in the top left corner of the component, the process group is now saved as a versioned flow in the registry.

ABCD Flow Saved
Go back to the Registry UI and return to the main page to see the versioned flow you just saved (a refresh may be required):

ABCD Flow in Test Bucket
Save Changes to a Versioned Flow
Changes made to the versioned process group can be reviewed, reverted or saved.

For example, if changes are made to the ABCD flow, the Version State changes to "Locally modified" (Locally Modified Icon). The right-click menu will now show the options "Commit local changes", "Show local changes" or "Revert local changes":

Changed Flow Options
Select "Show local changes" to see the details of the changes made:

Show ABCD Flow Changes
Select "Commit local changes", enter comments and select "Save" to save the changes:

Save ABCD Version 2
Version 2 of the flow is saved:

ABCD Version 2
Import a Versioned Flow
With a flow existing in the registry, we can use it to illustrate how to import a versioned process group.

In NiFi, select Process Group from the Components toolbar and drag it onto the canvas:

Drag Process Group
Instead of entering a name, click the Import link:

Import Flow From Registry
Choose the version of the flow you want imported and select "Import":

Import ABCD Version 2

A second identical PG is now added:

Two ABCD Flow on Canvas


NIFI_FLOW_CONFIGURATION_FILE: "/opt/nifi/data/conf/flow.json.gz"

NIFI_FLOWFILE_REPOSITORY_DIRECTORY: "/opt/nifi/data/flowfile_repository"
NIFI_CONTENT_REPOSITORY_DIRECTORY_DEFAULT: "/opt/nifi/data/content_repository"
NIFI_PROVENANCE_REPOSITORY_DIRECTORY_DEFAULT: "/opt/nifi/data/provenance_repository"
NIFI_DATABASE_DIRECTORY: "/opt/nifi/data/database_repository"
