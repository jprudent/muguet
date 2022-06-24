Oh Oh Oh let me tell you about Muguet!

Muguet helps develop fullstack applications.

At the heart of Muguet
is a declarative schema language.
It helps express applicative model:
identity and values, 
constraints and invariants,
relations with arity,
documentation and meta-data.

From your rich schema,
Data can be persisted and queried.
Muguet is not an ORM.
ORM stands for Object Relational Mapping.
No _objects_ nor _relational_ algebra behind the scene!
Backed by XTDB,
It stores a graph of aggregates.

Muguet is a time-traveler,
You can query past, present and future data.

From your rich schema,
Muguet will generate:
A model library (for browser and backend JS engines, Java and Clojure)
APIs (REST, GraphQL, Datalog, name your poison),
CLI tools & basic GUI,
Data extractors,
And documentations (textual, graphical & interactive)

An application is not an island,
Muguet is event based,
It will integrate with your information system.

An application is not an island,
Muguet is fully internationalized.

An application is not an island,
Muguet can reuse code thanks to plugin system.

Muguet is a dumb tool, 
Your business logic is smart,
So custom code is always possible.


From the schema of the model, Muguet generates a lot 
Persistence is backed by XTDB that can store data in different kind of database.
You can choose the right tool for the right job.


### TODO list demo




## Notes 

Runs on NodeJS and JVM

Schemas can be written in JSON, following the strapi notation

Inspired a lot from Strapi

Tailwind + Figwheel
https://curiousprogrammer.dev/blog/how-can-i-use-tailwind-in-my-clojure-script-web-app/


Use native web platform
https://www.jackfranklin.co.uk/blog/working-with-react-and-the-web-platform/

Multimedia library
- provide image resizing capabilities

Authentication:
- give granular responsibilities

Provide rich types, like blog post ?

Document lifecycle (do better than Strapi)

Be event based, so can plug other systems

Be temporality

Localization

Build apps

Developer oriented (aka not just a CRUD): web content definition is created once 
and users will have specific needs over time. A CMS may not be the right piece
of software to implement business logic. But Muguet is a different type of
CMS that provides developer tools for customisizing the user experience
beyond basic needs.

Infinitite relations, reusable infinitely, no quirks (like in Strapi "components")

Development workflow must be seamless (auto-reloading, repl-driven, ...)

Plugins & Plugin exentions (to improve base plugins)

Provides other ports than http

What about free form documents ?

Inferring schemas

Providing generators

i18n & localization

Deeply nested documents

Write a schema converter from Strapi to Muguet

Messages d'erreur i18n

Schemas must be extensible with 3rd-party libs that provides rich types. For 
instance EAN, or RDF types !

see how it compares with stuff in there https://news.ycombinator.com/item?id=31469481


data mapper https://en.wikipedia.org/wiki/Data_mapper_pattern VS active record :

Active record is hard coupling objects with relational model (tables, column, ...). 
This is quite good for prototyping quick, small, and short living applications. 
It doesn't scale with complexity.

Data mapper decouples model and persistance
and is what you need for application growth.

But if in your app you don't have "objects" but data 
and your backend is a graph,
why active record wouldn't be suitable for complex app ?
There is a perfect fit between a grape of data and a graph,
But it always come to performance.
And performance always come to destructuration.
You would destructure your graph to gain performance,
And your model would start to drift away from your persistance.
And your model starts to drift away from your business
And your model starts to have that gangrenous anemy.


## An ORM for the rest of us

Browsing HN, people have great technical challenge,
Mos of the time, I do dumb things.
I want a dumb tool to do dumb things.
